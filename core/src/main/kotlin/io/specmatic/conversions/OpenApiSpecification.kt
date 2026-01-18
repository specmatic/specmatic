@file:Suppress("UNCHECKED_CAST", "UNUSED_EXPRESSION")

package io.specmatic.conversions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.cucumber.messages.types.Step
import io.ktor.util.reflect.*
import io.specmatic.conversions.SchemaUtils.mergeResolvedIfJsonSchema
import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.conversions.lenient.DEFAULT_ARRAY_INDEX
import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.log.LogStrategy
import io.specmatic.core.log.logger
import io.specmatic.core.overlay.OverlayMerger
import io.specmatic.core.overlay.OverlayParser
import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.Discriminator
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.IGNORE_INLINE_EXAMPLE_WARNINGS
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue
import io.specmatic.core.utilities.toValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.BinarySchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.*
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.core.models.SwaggerParseResult
import io.swagger.v3.parser.util.ClasspathHelper
import java.io.File
import kotlin.collections.orEmpty

private const val BEARER_SECURITY_SCHEME = "bearer"

private const val X_SPECMATIC_HINT = "x-specmatic-hint"
private const val HINT_BOUNDARY_TESTING_ENABLED = "boundary_testing_enabled"
private val HINT_VALUE_DELIMITERS = charArrayOf(',')

const val testDirectoryEnvironmentVariable = "SPECMATIC_TESTS_DIRECTORY"
const val testDirectoryProperty = "specmaticTestsDirectory"

var missingRequestExampleErrorMessageForTest: String = "WARNING: Ignoring response example named %s for test or stub data, because no associated request example named %s was found."
var missingResponseExampleErrorMessageForTest: String = "WARNING: Ignoring request example named %s for test or stub data, because no associated response example named %s was found."

internal fun missingRequestExampleErrorMessageForTest(exampleName: String): String =
    missingRequestExampleErrorMessageForTest.format(exampleName, exampleName)

internal fun missingResponseExampleErrorMessageForTest(exampleName: String): String =
    missingResponseExampleErrorMessageForTest.format(exampleName, exampleName)

private const val SPECMATIC_TEST_WITH_NO_REQ_EX = "SPECMATIC-TEST-WITH-NO-REQ-EX"

data class OperationMetadata(
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val description: String = "",
    val operationId: String = ""
)

class OpenApiSpecification(
    private val openApiFilePath: String,
    val parsedOpenApi: OpenAPI,
    private val sourceProvider: String? = null,
    private val sourceRepository: String? = null,
    private val sourceRepositoryBranch: String? = null,
    private val specificationPath: String? = null,
    private val securityConfiguration: SecurityConfiguration? = null,
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
    private val strictMode: Boolean = false,
    private val lenientMode: Boolean = false,
    private val dictionary: Dictionary = loadDictionary(openApiFilePath, specmaticConfig.getStubDictionary(), strictMode),
    private val logger: LogStrategy = io.specmatic.core.log.logger
) : IncludedSpecification, ApiSpecification {
    init {
        StringProviders // Trigger early initialization of StringProviders to ensure all providers are loaded at startup
        logger.log(openApiSpecificationInfo(openApiFilePath, parsedOpenApi))
    }

    companion object {
        private val jsonMapper = ObjectMapper().registerKotlinModule()
        private const val X_CONST_EXPLICIT = "x-const-explicit"
        private const val NULL_TYPE = "null"
        private const val OBJECT_TYPE = "object"
        private const val ARRAY_TYPE = "array"
        private const val BINARY_FORMAT = "binary"

        fun patternsFrom(jsonSchema: Map<String, Any?>, schemaName: String = "Schema"): Map<String, Pattern> {
            val definitions = try {
                (jsonSchema["${'$'}defs"] as? Map<String, Any>).orEmpty()
            } catch (_: Throwable) {
                emptyMap()
            }

            val openApiMap = mapOf(
                "openapi" to "3.0.1",
                "components" to mapOf(
                    "schemas" to mapOf(
                        schemaName to replaceDefsReferences(jsonSchema)
                    ).plus(definitions)
                ),
            )
            val openApiSpec = fromYAML(
                yamlContent = ObjectMapper().writeValueAsString(openApiMap),
                openApiFilePath = ""
            )

            return openApiSpec.parseUnreferencedSchemas()
        }

        private fun replaceDefsReferences(jsonSchema: Map<String, Any?>): Map<String, Any?> {
            return jsonSchema.mapValues { (key, value) ->
                when (value) {
                    is String -> {
                        when {
                            key == "\$ref" && value.startsWith("#/\$defs/") -> value.replace(
                                "#/\$defs/",
                                "#/components/schemas/"
                            )

                            else -> value
                        }
                    }

                    is Map<*, *> -> replaceDefsReferences(value as Map<String, Any?>)
                    is List<*> -> {
                        value.map { item ->
                            when (item) {
                                is Map<*, *> -> replaceDefsReferences(item as Map<String, Any?>)
                                else -> item
                            }
                        }
                    }

                    else -> value
                }
            }
        }

        fun fromFile(openApiFilePath: String, relativeTo: String = "", lenientMode: Boolean = false): OpenApiSpecification {
            val openApiFile = File(openApiFilePath).let { openApiFile ->
                if (openApiFile.isAbsolute) {
                    openApiFile
                } else {
                    File(relativeTo).canonicalFile.parentFile.resolve(openApiFile)
                }
            }

            return fromFile(openApiFile.canonicalPath, lenientMode)
        }

        fun fromFile(openApiFilePath: String, lenientMode: Boolean = false): OpenApiSpecification {
            return fromFile(openApiFilePath, SpecmaticConfig(), lenientMode)
        }

        fun fromFile(openApiFilePath: String, specmaticConfig: SpecmaticConfig, lenientMode: Boolean = false): OpenApiSpecification {
            val specContent = sequenceOf(
                { File(openApiFilePath).readText() },
                { ClasspathHelper.loadFileFromClasspath(openApiFilePath) },
            ).firstNotNullOfOrNull {
                runCatching { it.invoke() }.getOrElse { e ->
                    logger.debug(e, "Failed to read OpenApi Specification")
                    null
                }
            }

            if (specContent == null) throw ContractException(
                errorMessage = "Failed to read OpenApi Specification from $openApiFilePath",
                breadCrumb = openApiFilePath,
            )

            return runCatching {
                fromYAML(specContent, openApiFilePath, specmaticConfig = specmaticConfig, lenientMode = lenientMode)
            }.getOrElse { e ->
                // TODO: Fix BackwardCompatibilityCheck to not pass example json as OpenAPI files to avoid fallback here
                logger.debug(e, "Failed to parse specification $openApiFilePath using fromYAML")
                OpenApiSpecification(openApiFilePath, getParsedOpenApi(openApiFilePath), specmaticConfig = specmaticConfig)
            }
        }

        fun getParsedOpenApi(openApiFilePath: String): OpenAPI {
            return OpenAPIV3Parser().read(openApiFilePath, null, resolveExternalReferences())
        }

        fun isParsable(openApiFilePath: String): Boolean {
            return OpenAPIV3Parser().read(openApiFilePath, null, resolveExternalReferences()) != null
        }

        fun getImplicitOverlayContent(openApiFilePath: String): String {
            return File(openApiFilePath).let { openApiFile ->
                if (!openApiFile.isFile) {
                    return@let ""
                }

                val overlayFile = openApiFile.canonicalFile.parentFile.resolve(openApiFile.nameWithoutExtension + "_overlay.yaml")
                if(overlayFile.isFile) return@let overlayFile.readText()

                return@let ""
            }
        }

        fun checkSpecValidity(openApiFilePath: String, lenientMode: Boolean = false) {
            val parseResult: SwaggerParseResult =
                OpenAPIV3Parser().readContents(
                    checkExists(File(openApiFilePath)).readText(),
                    null,
                    resolveExternalReferences(),
                    openApiFilePath,
                )
            if (parseResult.openAPI == null) {
                throw ContractException("Could not parse contract $openApiFilePath, please validate the syntax using https://editor.swagger.io")
            }
            if (!lenientMode && parseResult.messages?.isNotEmpty() == true) {
                throw ContractException(
                    "The OpenAPI file $openApiFilePath was read successfully but with some issues: ${
                        parseResult.messages.joinToString(
                            "\n",
                        )
                    }",
                )
            }
        }

        fun fromYAML(
            yamlContent: String,
            openApiFilePath: String,
            logger: LogStrategy = io.specmatic.core.log.logger,
            sourceProvider: String? = null,
            sourceRepository: String? = null,
            sourceRepositoryBranch: String? = null,
            specificationPath: String? = null,
            securityConfiguration: SecurityConfiguration? = null,
            specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
            overlayContent: String = "",
            strictMode: Boolean = false,
            lenientMode: Boolean = false,
        ): OpenApiSpecification {
            val implicitOverlayFile = getImplicitOverlayContent(openApiFilePath)
            val mergedYaml = yamlContent.applyOverlay(overlayContent).applyOverlay(implicitOverlayFile)
            val preprocessedYaml = preprocessYamlForAdditionalProperties(mergedYaml)

            val parseResult: SwaggerParseResult =
                OpenAPIV3Parser().readContents(
                    preprocessedYaml,
                    null,
                    resolveExternalReferences(),
                    openApiFilePath.replace("\\", "/")
                )
            val parsedOpenApi: OpenAPI? = parseResult.openAPI

            if (parsedOpenApi == null) {
                logger.log("FATAL: Failed to parse OpenAPI from file $openApiFilePath after preprocessing additionalProperties\n\n$preprocessedYaml")

                printMessages(parseResult, logger)

                throw ContractException("Could not parse contract $openApiFilePath, please validate the syntax using https://editor.swagger.io")
            } else if (parseResult.messages?.isNotEmpty() == true) {
                logger.boundary()
                logger.log("WARNING: The OpenAPI file $openApiFilePath was read successfully but with some issues")

                logger.withIndentation(2) {
                    printMessages(parseResult, logger)
                }
                logger.boundary()
            }

            return OpenApiSpecification(
                openApiFilePath,
                parsedOpenApi,
                sourceProvider,
                sourceRepository,
                sourceRepositoryBranch,
                specificationPath,
                securityConfiguration,
                specmaticConfig,
                strictMode = strictMode,
                lenientMode = lenientMode,
                logger = logger
            )
        }

        fun loadDictionary(openApiFilePath: String, dictionaryPathFromConfig: String?, strictMode: Boolean = false): Dictionary {
            val dictionaryFile = getDictionaryFile(File(openApiFilePath), dictionaryPathFromConfig)
            return if (dictionaryFile != null) Dictionary.from(dictionaryFile, strictMode) else Dictionary.empty(strictMode)
        }

        private fun getDictionaryFile(openApiFile: File, dictionaryPathFromConfig: String?): File? {
            val explicitDictionaryPath = dictionaryPathFromConfig ?: Flags.getStringValue(SPECMATIC_STUB_DICTIONARY)
            if (!explicitDictionaryPath.isNullOrEmpty()) return File(explicitDictionaryPath)

            val implicitPaths = sequenceOf("_dictionary.yml", "_dictionary.yaml", "_dictionary.json")
            return implicitPaths.map {
                openApiFile.canonicalFile.parentFile.resolve(openApiFile.nameWithoutExtension + it)
            }.firstOrNull(File::exists)
        }

        private fun printMessages(parseResult: SwaggerParseResult, loggerForErrors: LogStrategy) {
            parseResult.messages.filterNotNull().let { message ->
                if (message.isNotEmpty()) {
                    val parserMessages = parseResult.messages.joinToString(System.lineSeparator()) { "- $it" }
                    loggerForErrors.log(parserMessages)
                }
            }
        }

        private fun resolveExternalReferences(): ParseOptions {
            return ParseOptions().also {
                it.isResolve = true
                it.isResolveRequestBody = true
                it.isResolveResponses = true
            }
        }

        private val yamlPreprocessorMapper: ObjectMapper by lazy {
            ObjectMapper(
                YAMLFactory()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER),
            ).registerKotlinModule()
        }

        private fun preprocessYamlForAdditionalProperties(yaml: String): String {
            if (yaml.isBlank()) return yaml

            return try {
                val root = yamlPreprocessorMapper.readTree(yaml) ?: return yaml
                removeInvalidAdditionalProperties(root)
                yamlPreprocessorMapper.writeValueAsString(root)
            } catch (exception: Exception) {
                logger.debug("Skipping additionalProperties preprocessing due to error: ${exception.message}")
                yaml
            }
        }

        private fun removeInvalidAdditionalProperties(node: JsonNode?, skipProcessing: Boolean = false, currentPath: String = "") {
            if (node == null || skipProcessing) return

            when (node) {
                is ObjectNode -> {
                    if (node.has("additionalProperties") && shouldRemoveAdditionalProperties(node.get("type"))) {
                        val logPath = currentPath.ifBlank { "root" }
                        val typeDescription = node.get("type")?.let { typeNode ->
                            when {
                                typeNode.isTextual -> typeNode.asText()
                                typeNode.isNull -> "null"
                                else -> typeNode.toString()
                            }
                        } ?: "unspecified"
                        logger.debug("Ignoring 'additionalProperties' from $logPath (additionalProperties only applies to 'type: object', but found 'type: $typeDescription')")
                        node.remove("additionalProperties")
                    }

                    val fieldNames = node.fieldNames()
                    while (fieldNames.hasNext()) {
                        val fieldName = fieldNames.next()
                        val value = node.get(fieldName)
                        val shouldSkipChild = fieldName == "example" || fieldName == "examples"
                        val childPath = when {
                            currentPath.isBlank() -> fieldName
                            else -> "$currentPath.$fieldName"
                        }

                        removeInvalidAdditionalProperties(value, shouldSkipChild, childPath)
                    }
                }

                is ArrayNode -> {
                    node.forEachIndexed { index, element ->
                        val elementPath = when {
                            currentPath.isBlank() -> index.toString()
                            else -> "$currentPath.$index"
                        }
                        removeInvalidAdditionalProperties(element, skipProcessing, elementPath)
                    }
                }
            }
        }

        private fun shouldRemoveAdditionalProperties(typeNode: JsonNode?): Boolean {
            if (typeNode == null) return false

            if (typeNode.isTextual) {
                return !typeNode.asText().equals(OBJECT_TYPE, ignoreCase = true)
            }

            return true
        }

        fun String.applyOverlay(overlayContent: String): String {
            if(overlayContent.isBlank())
                return this

            return OverlayMerger().merge(this, OverlayParser.parse(overlayContent))
        }
    }

    val patterns = mutableMapOf<String, Pattern>()
    val protocol = SpecmaticProtocol.HTTP

    fun isOpenAPI31(): Boolean {
        return parsedOpenApi.openapi.startsWith("3.1")
    }

    fun toFeature(): Feature {
        val (feature, result) = toFeatureLenient()
        return result.returnLenientlyElseFail(lenientMode, feature)
    }

    fun toFeatureLenient(): Pair<Feature, Result> {
        val rootContext = CollectorContext()
        val name = File(openApiFilePath).name

        val (scenarioInfos, stubsFromExamples) = toScenarioInfos(rootContext)
        val unreferencedSchemaPatterns = parseUnreferencedSchemas(rootContext)
        val updatedScenarios = scenarioInfos.map {
            Scenario(it).copy(
                dictionary = dictionary.plus(specmaticConfig.parsedDefaultPatternValues()),
                attributeSelectionPattern = specmaticConfig.getAttributeSelectionPattern(),
                patterns = it.patterns + unreferencedSchemaPatterns
            )
        }

        val feature = Feature.from(
            updatedScenarios, name = name, path = openApiFilePath, sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            specification = specificationPath,
            stubsFromExamples = stubsFromExamples,
            specmaticConfig = specmaticConfig,
            strictMode = strictMode,
            protocol = protocol
        )

        return Pair(feature, rootContext.toCollector().toResult())
    }

    fun parseUnreferencedSchemas(): Map<String, Pattern> {
        val context = CollectorContext()
        val patterns = parseUnreferencedSchemas(context)
        return context.toCollector().toResult().returnLenientlyElseFail(lenientMode, patterns)
    }

    fun parseUnreferencedSchemas(rootContext: CollectorContext): Map<String, Pattern> {
        val componentSchemasContext = rootContext.at("components").at("schemas")
        return openApiSchemas().filterNot { withPatternDelimiters(it.key) in patterns }.map {
            val schemaContext = componentSchemasContext.at(it.key)
            withPatternDelimiters(it.key) to schemaContext.safely(
                message = "Failed to convert schema to internal format, defaulting to any schema",
                fallback = { AnythingPattern },
                block = { schemaScope ->
                    toSpecmaticPattern(schema = it.value, typeStack = emptyList(), patternName = it.key, collectorContext = schemaScope)
                }
            )
        }.toMap()
    }

    fun getServers(): List<Server> {
        return parsedOpenApi.servers.orEmpty()
    }

    override fun toScenarioInfos(): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val rootContext = CollectorContext()
        val scenarioInfos = toScenarioInfos(rootContext)
        return rootContext.toCollector().toResult().returnLenientlyElseFail(lenientMode, scenarioInfos)
    }

    fun toScenarioInfos(rootContext: CollectorContext): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val (
            scenarioInfos: List<ScenarioInfo>,
            examplesAsExpectations: Map<String, List<Pair<HttpRequest, HttpResponse>>>
        ) = openApiToScenarioInfos(rootContext)

        return scenarioInfos.filter { it.httpResponsePattern.status > 0 } to examplesAsExpectations
    }

    override fun matches(specmaticScenarioInfo: ScenarioInfo, steps: List<Step>): List<ScenarioInfo> {
        val (openApiScenarioInfos, _) = openApiToScenarioInfos(CollectorContext())
        if (openApiScenarioInfos.isEmpty() || steps.isEmpty()) return listOf(specmaticScenarioInfo)
        val result: MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> =
            specmaticScenarioInfo to openApiScenarioInfos to ::matchesPath then ::matchesMethod then ::matchesStatus then ::updateUrlMatcher otherwise ::handleError
        when (result) {
            is MatchFailure -> throw ContractException(result.error.message)
            is MatchSuccess -> return result.value.second
        }
    }

    private fun matchesPath(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        // exact + exact   -> values should be equal
        // exact + pattern -> error
        // pattern + exact -> pattern should match exact
        // pattern + pattern -> both generated concrete values should be of same type

        val matchingScenarioInfos = specmaticScenarioInfo.matchesGherkinWrapperPath(openApiScenarioInfos, this)

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" PATH: "${
                        specmaticScenarioInfo.httpRequestPattern.httpPathPattern!!.generate(Resolver())
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )

            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    override fun patternMatchesExact(
        wrapperURLPart: URLPathSegmentPattern,
        openapiURLPart: URLPathSegmentPattern,
        resolver: Resolver,
    ): Boolean {
        val valueFromWrapper = (wrapperURLPart.pattern as ExactValuePattern).pattern

        val valueToMatch: Value =
            if (valueFromWrapper is StringValue) {
                openapiURLPart.pattern.parse(valueFromWrapper.toStringLiteral(), resolver)
            } else {
                wrapperURLPart.pattern.pattern
            }

        return openapiURLPart.pattern.matches(valueToMatch, resolver) is Result.Success
    }

    override fun exactValuePatternsAreEqual(
        openapiURLPart: URLPathSegmentPattern,
        wrapperURLPart: URLPathSegmentPattern
    ) =
        (openapiURLPart.pattern as ExactValuePattern).pattern.toStringLiteral() == (wrapperURLPart.pattern as ExactValuePattern).pattern.toStringLiteral()

    private fun matchesMethod(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos =
            openApiScenarioInfos.filter { it.httpRequestPattern.method == specmaticScenarioInfo.httpRequestPattern.method }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" METHOD: "${
                        specmaticScenarioInfo.httpRequestPattern.method
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )

            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun matchesStatus(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos =
            openApiScenarioInfos.filter { it.httpResponsePattern.status == specmaticScenarioInfo.httpResponsePattern.status }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" RESPONSE STATUS: "${
                        specmaticScenarioInfo.httpResponsePattern.status
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )

            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun updateUrlMatcher(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        return MatchSuccess(specmaticScenarioInfo to openApiScenarioInfos.map { openApiScenario ->
            val queryPattern = openApiScenario.httpRequestPattern.httpQueryParamPattern.queryPatterns
            val zippedPathPatterns =
                (specmaticScenarioInfo.httpRequestPattern.httpPathPattern?.pathSegmentPatterns ?: emptyList()).zip(
                    openApiScenario.httpRequestPattern.httpPathPattern?.pathSegmentPatterns ?: emptyList()
                )

            val pathPatterns = zippedPathPatterns.map { (fromWrapper, fromOpenApi) ->
                if (fromWrapper.pattern is ExactValuePattern)
                    fromWrapper
                else
                    fromOpenApi.copy(key = fromWrapper.key)
            }

            val httpPathPattern =
                HttpPathPattern(pathPatterns, openApiScenario.httpRequestPattern.httpPathPattern?.path ?: "")
            val httpQueryParamPattern = HttpQueryParamPattern(queryPattern)

            val httpRequestPattern = openApiScenario.httpRequestPattern.copy(
                httpPathPattern = httpPathPattern,
                httpQueryParamPattern = httpQueryParamPattern
            )
            openApiScenario.copy(httpRequestPattern = httpRequestPattern)
        })
    }

    data class RequestPatternsData(val requestPattern: HttpRequestPattern, val examples: Map<String, List<HttpRequest>>, val original: Pair<String, MediaType>? = null)

    private fun openApiToScenarioInfos(rootContext: CollectorContext): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val pathsContext = rootContext.at("paths")
        val allPathPatternsGroupedByMethod = allPathPatternsGroupedByMethod(pathsContext)

        val data: List<Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>>> =
            openApiPaths().map { (openApiPath, pathItem) ->
                val scenariosAndExamples = openApiOperations(pathItem).map { (httpMethod, openApiOperation) ->
                    logger.debug("${System.lineSeparator()}Processing $httpMethod $openApiPath")

                    val methodContext = pathsContext.at(openApiPath).at(httpMethod.lowercase())
                    val specmaticPathParam = toSpecmaticPathParam(
                        openApiPath = openApiPath,
                        operation = openApiOperation,
                        otherPathPatterns = allPathPatternsGroupedByMethod[httpMethod].orEmpty(),
                        collectorContext = methodContext
                    )

                    val specmaticQueryParam = toSpecmaticQueryParam(operation = openApiOperation, collectorContext = methodContext)
                    val httpResponsePatterns: List<ResponsePatternData> = attempt(breadCrumb = "$httpMethod $openApiPath -> RESPONSE") {
                        toHttpResponsePatterns(responses = openApiOperation.responses, collectorContext = methodContext)
                    }

                    val first2xxResponseStatus =
                        httpResponsePatterns.filter { it.responsePattern.status.toString().startsWith("2") }
                            .minOfOrNull { it.responsePattern.status }

                    val firstNoBodyResponseStatus =
                        httpResponsePatterns.filter { it.responsePattern.body is NoBodyPattern }
                            .minOfOrNull { it.responsePattern.status }

                    val httpResponsePatternsGrouped = httpResponsePatterns.groupBy { it.responsePattern.status }

                    val httpRequestPatterns: List<RequestPatternsData> =
                        attempt("In $httpMethod $openApiPath request") {
                            toHttpRequestPatterns(
                                httpPathPattern = specmaticPathParam,
                                httpQueryParamPattern = specmaticQueryParam,
                                httpMethod = httpMethod,
                                operation = openApiOperation,
                                collectorContext = methodContext
                            )
                        }

                    val httpRequestPatternDataGroupedByContentType = httpRequestPatterns.groupBy {
                        it.requestPattern.headersPattern.contentType
                    }

                    val requestMediaTypes = httpRequestPatternDataGroupedByContentType.keys

                    val requestResponsePairs = httpResponsePatternsGrouped.flatMap { (_, responses) ->
                        val responsesGrouped = responses.groupBy {
                            it.responsePattern.headersPattern.contentType
                        }

                        if (responsesGrouped.keys.filterNotNull().toSet() == requestMediaTypes.filterNotNull().toSet()) {
                            responsesGrouped.map { (contentType, responsesData) ->
                                httpRequestPatternDataGroupedByContentType.getValue(contentType)
                                    .single() to responsesData.single()
                            }
                        } else {
                            responses.flatMap { responsePatternData ->
                                httpRequestPatterns.map { requestPatternData ->
                                    requestPatternData to responsePatternData
                                }
                            }
                        }

                    }

                    val scenarioInfos = requestResponsePairs.map { (requestPatternData, responsePatternData) ->
                        val (httpRequestPattern, requestExamples: Map<String, List<HttpRequest>>, openApiRequest) = requestPatternData
                        val (response, _: MediaType, httpResponsePattern, responseExamples: Map<String, HttpResponse>) = responsePatternData

                        val specmaticExampleRows: List<Row> =
                            testRowsFromExamples(responseExamples, requestExamples, openApiOperation, openApiRequest, first2xxResponseStatus)
                        val scenarioName = scenarioName(openApiOperation, response, httpRequestPattern)

                        val ignoreFailure = openApiOperation.tags.orEmpty().map { it.trim() }.contains("WIP")

                        val rowsToBeUsed: List<Row> = specmaticExampleRows

                        val operationMetadata = OperationMetadata(
                            tags = openApiOperation.tags.orEmpty(),
                            summary = openApiOperation.summary.orEmpty(),
                            description = openApiOperation.description.orEmpty(),
                            operationId = openApiOperation.operationId.orEmpty()
                        )

                        ScenarioInfo(
                            scenarioName = scenarioName,
                            patterns = patterns.toMap(),
                            httpRequestPattern = httpRequestPattern,
                            httpResponsePattern = httpResponsePattern,
                            ignoreFailure = ignoreFailure,
                            examples = rowsToExamples(rowsToBeUsed),
                            sourceProvider = sourceProvider,
                            sourceRepository = sourceRepository,
                            sourceRepositoryBranch = sourceRepositoryBranch,
                            specification = specificationPath,
                            protocol = protocol,
                            specType = SpecType.OPENAPI,
                            operationMetadata = operationMetadata
                        )
                    }

                    val responseExamplesList = httpResponsePatterns.map { it.examples }

                    val requestExamples = httpRequestPatterns.map {
                        it.examples
                    }.foldRight(emptyMap<String, List<HttpRequest>>()) { acc, map ->
                        acc.plus(map)
                    }

                    val examples = collateExamplesForExpectations(requestExamples, responseExamplesList)

                    val requestExampleNames = requestExamples.keys

                    val usedExamples = examples.keys

                    val unusedRequestExampleNames = requestExampleNames - usedExamples

                    val responseThatReturnsNoValues = httpResponsePatterns.find { responsePatternData ->
                        responsePatternData.responsePattern.body == NoBodyPattern
                                && responsePatternData.responsePattern.status == firstNoBodyResponseStatus
                    }

                    val (additionalExamples, updatedScenarios)
                            = when {
                                responseThatReturnsNoValues != null && unusedRequestExampleNames.isNotEmpty() -> {
                                    getUpdatedScenarioInfosWithNoBodyResponseExamples(
                                        responseThatReturnsNoValues,
                                        requestExamples,
                                        unusedRequestExampleNames,
                                        scenarioInfos,
                                        openApiOperation,
                                        firstNoBodyResponseStatus
                                    )
                                }

                                else -> emptyMap<String, List<Pair<HttpRequest, HttpResponse>>>() to scenarioInfos
                            }

                    Triple(updatedScenarios, examples + additionalExamples, requestExampleNames)
                }

                val requestExampleNames = scenariosAndExamples.flatMap { it.third }.toSet()

                val usedExamples = scenariosAndExamples.flatMap { it.second.keys }.toSet()

                val unusedRequestExampleNames = requestExampleNames - usedExamples

                if(getBooleanValue(IGNORE_INLINE_EXAMPLE_WARNINGS).not()) {
                    unusedRequestExampleNames.forEach { unusedRequestExampleName ->
                        // TODO: Collect as warning
                        logger.log(missingResponseExampleErrorMessageForTest(unusedRequestExampleName))
                    }
                }

                scenariosAndExamples.map {
                    it.first to it.second
                }
            }.flatten()


        val scenarioInfos = data.map { it.first }.flatten()
        val examples: Map<String, List<Pair<HttpRequest, HttpResponse>>> =
            data.map { it.second }.foldRight(emptyMap()) { acc, map ->
                acc.plus(map)
            }

        logger.boundary()
        return scenarioInfos to examples
    }

    private fun allPathPatternsGroupedByMethod(collectorContext: CollectorContext): Map<String, List<HttpPathPattern>> {
        return openApiPaths().flatMap { (openApiPath, pathItem) ->
            val pathContext = collectorContext.at(openApiPath)
            openApiOperations(pathItem).map { (httpMethod, openApiOperation) ->
                val methodContext = pathContext.at(httpMethod.lowercase())
                httpMethod to toSpecmaticPathParam(openApiPath = openApiPath, operation = openApiOperation, collectorContext = methodContext)
            }
        }.groupBy({ it.first }, { it.second })
    }

    private fun getUpdatedScenarioInfosWithNoBodyResponseExamples(
        responseThatReturnsNoValues: ResponsePatternData,
        requestExamples: Map<String, List<HttpRequest>>,
        unusedRequestExampleNames: Set<String>,
        scenarioInfos: List<ScenarioInfo>,
        operation: Operation,
        firstNoBodyResponseStatus: Int?,
    ): Pair<Map<String, List<Pair<HttpRequest, HttpResponse>>>, List<ScenarioInfo>> {
        val emptyResponse = HttpResponse(
            status = responseThatReturnsNoValues.responsePattern.status,
            headers = emptyMap(),
            body = NoBodyValue
        )
        val examplesOfResponseThatReturnsNoValues: Map<String, List<Pair<HttpRequest, HttpResponse>>> =
            requestExamples.filterKeys { it in unusedRequestExampleNames }
                .mapValues { (_, examples) ->
                    examples.map { it to emptyResponse }
                }

        val updatedScenarioInfos = scenarioInfos.map { scenarioInfo ->
            if (scenarioInfo.httpResponsePattern.body == NoBodyPattern
                && scenarioInfo.httpResponsePattern.status == firstNoBodyResponseStatus
            ) {
                val unusedRequestExample =
                    requestExamples.filter { it.key in unusedRequestExampleNames }

                val rows = getRowsFromRequestExample(unusedRequestExample, operation, scenarioInfo)

                val updatedExamples: List<Examples> = listOf(
                    Examples(
                        rows.first().columnNames,
                        scenarioInfo.examples.firstOrNull()?.rows.orEmpty() + rows
                    )
                )
                scenarioInfo.copy(
                    examples = updatedExamples
                )
            } else
                scenarioInfo
        }

        return examplesOfResponseThatReturnsNoValues to updatedScenarioInfos
    }

    private fun getRowsFromRequestExample(
        requestExample: Map<String, List<HttpRequest>>,
        operation: Operation,
        scenarioInfo: ScenarioInfo
    ): List<Row> {
        return requestExample.flatMap { (key, requests) ->
            requests.map { request ->
                val paramExamples = (request.headers + request.queryParams.asMap()).toList()
                val pathParameterExamples = try {
                    parameterExamples(operation, key).mapValues { (it.value as? String) ?: jsonMapper.writeValueAsString(it.value) }
                } catch (_: Exception) {
                    emptyMap()
                }.entries.map { it.key to it.value }


                val allExamples = if (scenarioInfo.httpRequestPattern.body is NoBodyPattern) {
                    paramExamples + pathParameterExamples
                } else
                    listOf("(REQUEST-BODY)" to request.body.toStringLiteral()) + paramExamples
                Row(
                    name = key,
                    columnNames = allExamples.map { it.first },
                    values = allExamples.map { it.second }
                )
            }
        }
    }

    private fun collateExamplesForExpectations(
        requestExamples: Map<String, List<HttpRequest>>,
        responseExamplesList: List<Map<String, HttpResponse>>
    ): Map<String, List<Pair<HttpRequest, HttpResponse>>> {
        return responseExamplesList.flatMap { responseExamples ->
            responseExamples.filter { (key, _) ->
                key in requestExamples
            }.map { (key, responseExample) ->
                key to requestExamples.getValue(key).map { it to responseExample }
            }
        }.toMap()
    }

    private fun scenarioName(
        operation: Operation,
        response: ApiResponse,
        httpRequestPattern: HttpRequestPattern
    ): String = operation.summary?.let {
        """${operation.summary}. Response: ${response.description}"""
    } ?: "${httpRequestPattern.testDescription()}. Response: ${response.description}"

    private fun rowsToExamples(specmaticExampleRows: List<Row>): List<Examples> =
        when (specmaticExampleRows) {
            emptyList<Row>() -> emptyList()
            else -> {
                val examples = Examples(
                    specmaticExampleRows.first().columnNames,
                    specmaticExampleRows
                )

                listOf(examples)
            }
        }

    private fun testRowsFromExamples(
        responseExamples: Map<String, HttpResponse>,
        requestExampleAsHttpRequests: Map<String, List<HttpRequest>>,
        operation: Operation,
        openApiRequest: Pair<String, MediaType>?,
        first2xxResponseStatus: Int?
    ): List<Row> {

        return responseExamples.mapNotNull { (exampleName, responseExample) ->
            val parameterExamples: Map<String, Any> = parameterExamples(operation, exampleName)

            val requestBodyExample: Map<String, Any> =
                requestBodyExample(openApiRequest, exampleName, operation.summary)

            val requestExamples = parameterExamples.plus(requestBodyExample).map { (key, value) ->
                if (value.toString().contains("externalValue")) "${key}_filename" to value
                else key to value
            }.toMap().ifEmpty { mapOf(SPECMATIC_TEST_WITH_NO_REQ_EX to "") }

            if (requestExamples.containsKey(SPECMATIC_TEST_WITH_NO_REQ_EX) && responseExample.status != first2xxResponseStatus) {
                // TODO: Collect as warning
                if (getBooleanValue(IGNORE_INLINE_EXAMPLE_WARNINGS).not())
                    logger.log(missingRequestExampleErrorMessageForTest(exampleName))
                return@mapNotNull null
            }

            val resolvedResponseExample: ResponseExample? =
                when {
                    specmaticConfig.isResponseValueValidationEnabled() ->
                        ResponseValueExample(responseExample)

                    else ->
                        null
                }

            Row(
                requestExamples.keys.toList().map { keyName: String -> keyName },
                requestExamples.values.toList().map { value: Any? -> value?.toString() ?: "" }
                    .map { valueString: String ->
                        if (valueString.contains("externalValue")) {
                            ObjectMapper().readValue(valueString, Map::class.java).values.first().toString()
                        } else valueString
                    },
                name = exampleName,
                exactResponseExample = if(resolvedResponseExample != null && responseExample.isNotEmpty()) resolvedResponseExample else null,
                requestExample = requestExampleAsHttpRequests[exampleName]?.first(),
                responseExample = responseExample
            )
        }
    }

    data class OperationIdentifier(val requestMethod: String, val requestPath: String, val responseStatus: Int, val requestContentType: String?, val responseContentType: String?)

    private fun requestBodyExample(
        openApiRequest: Pair<String, MediaType>?,
        exampleName: String,
        operationSummary: String?
    ): Map<String, Any> {
        if(openApiRequest == null)
            return emptyMap()

        val (requestBodyContentType, requestBodyMediaType) = openApiRequest

        val requestExampleValue: Any? =
            resolveExample(requestBodyMediaType.examples?.get(exampleName))?.value

        val requestBodyExample: Map<String, Any> = if (requestExampleValue != null) {
            if (requestBodyContentType == "application/x-www-form-urlencoded" || requestBodyContentType == "multipart/form-data") {
                val operationSummaryClause = operationSummary?.let { "for operation \"${operationSummary}\"" } ?: ""
                val jsonExample =
                    attempt("Could not parse example $exampleName$operationSummaryClause") {
                        // TODO: Collect as error
                        parsedJSON(requestExampleValue.toString()) as JSONObjectValue
                    }
                jsonExample.jsonObject.map { (key, value) ->
                    key to value.toString()
                }.toMap()
            } else {
                mapOf("(REQUEST-BODY)" to requestExampleValue)
            }
        } else {
            emptyMap()
        }
        return requestBodyExample
    }

    private fun resolveExample(example: Example?): Example? {
        // TODO: Collect as error
        return example?.`$ref`?.let {
            val exampleName = it.substringAfterLast("/")
            parsedOpenApi.components?.examples?.get(exampleName)
        } ?: example
    }

    private fun parameterExamples(operation: Operation, exampleName: String): Map<String, Any> {
        return operation.parameters.orEmpty().safeFilter<Parameter>(CollectorContext()).filter { (_, parameter) ->
            parameter.examples.orEmpty().any { it.key == exampleName }
        }.associate { (_, parameter) ->
            // TODO: Collect as error
            val exampleValue: Example = parameter.examples[exampleName] ?: throw ContractException("The value of ${parameter.name} in example $exampleName was unexpectedly found to be null.")
            parameter.name to (resolveExample(exampleValue)?.value ?: "")
        }
    }

    private fun openApiPaths() = parsedOpenApi.paths.orEmpty()

    private fun openApiSchemas() = parsedOpenApi.components?.schemas.orEmpty()

    private fun isNumber(value: String): Boolean {
        return value.toIntOrNull() != null
    }

    private fun toHttpResponsePatterns(responses: ApiResponses?, collectorContext: CollectorContext): List<ResponsePatternData> {
        val responsesContext = collectorContext.at("responses")
        return responses.orEmpty().map { (status, response) ->
            logger.debug("Processing response payload with status $status")
            val statusContext = responsesContext.at(status)
            val (resolvedResponse, responseContext) = resolveResponse(response, statusContext)
            val headersMap = openAPIHeadersToSpecmatic(response, responseContext)
            val finalizedStatus = statusContext.check<String>(value = status, isValid = { isNumber(status) || status == "default" })
                .violation { OpenApiLintViolations.INVALID_OPERATION_STATUS }
                .message {
                    "The response status code must be a valid integer, or the string value \"default\", but was \"$status\". Please use a valid status code or remove the status code section."
                }
                .orUse { "default" }
                .build()

            attempt(breadCrumb = status) {
                openAPIResponseToSpecmatic(resolvedResponse, finalizedStatus, headersMap, responseContext)
            }
        }.flatten()
    }

    private fun openAPIHeadersToSpecmatic(response: ApiResponse, collectorContext: CollectorContext): Map<String, Pair<Pattern, CollectorContext>> {
        val headersContext = collectorContext.at("headers")
        return response.headers.orEmpty().map { (headerName, header) ->
            logger.debug("Processing response header $headerName")
            val parameterInternalName = toSpecmaticParamName(header.required != true, headerName)
            val (resolvedHeader, context) = resolveResponseHeader(header, headersContext.at(headerName))
            val headerToProcess = context.requirePojo(
                message = { "No schema found. Please add the missing schema." },
                extract = { resolvedHeader.schema },
                createDefault = { Schema<Any>() }
            )
            parameterInternalName to Pair(
                first = toSpecmaticPattern(schema = headerToProcess, typeStack = emptyList(), collectorContext = context.at("schema")),
                second = context
            )
        }.toMap()
    }

    data class ResponsePatternData(
        val response: ApiResponse,
        val mediaType: MediaType,
        val responsePattern: HttpResponsePattern,
        val examples: Map<String, HttpResponse>
    )

    private fun resolveResponseHeader(header: Header, collectorContext: CollectorContext): Pair<Header, CollectorContext> {
        if (header.`$ref` == null) return Pair(header, collectorContext)
        val headerComponentName = header.`$ref`.substringAfterLast("/")
        val hasReusableHeader = parsedOpenApi.components?.headers?.contains(headerComponentName) == true
        return Pair(
            first = collectorContext.at("\$ref").requirePojo(
                message = {
                    "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"$headerComponentName\"."
                },
                extract = { parsedOpenApi.components?.headers?.get(headerComponentName) },
                ruleViolation = { OpenApiLintViolations.UNRESOLVED_REFERENCE },
                createDefault = { Header().apply { schema = Schema<Any>(); required = false } },
            ),
            second = if (hasReusableHeader) {
                collectorContext.withPath("components").at("headers").at(headerComponentName)
            } else {
                collectorContext
            }
        )
    }

    private fun resolveResponse(response: ApiResponse, collectorContext: CollectorContext): Pair<ApiResponse, CollectorContext> {
        if (response.`$ref` == null) return Pair(response, collectorContext)
        val responseComponentName = response.`$ref`.substringAfterLast("/")
        val hasReusableResponse = parsedOpenApi.components?.responses?.contains(responseComponentName) == true
        return Pair(
            first = collectorContext.at("\$ref").requirePojo(
                message = {
                    "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"$responseComponentName\"."
                },
                extract = { parsedOpenApi.components?.responses?.get(responseComponentName) },
                ruleViolation = { OpenApiLintViolations.UNRESOLVED_REFERENCE },
                createDefault = { ApiResponse() }
            ),
            second = if (hasReusableResponse) {
                collectorContext.withPath("components").at("responses").at(responseComponentName)
            } else {
                collectorContext
            }
        )
    }

    private fun openAPIResponseToSpecmatic(response: ApiResponse, status: String, headersMap: Map<String, Pair<Pattern, CollectorContext>>, collectorContext: CollectorContext): List<ResponsePatternData> {
        val headerExamples =
            if (specmaticConfig.getIgnoreInlineExamples() || getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES))
                emptyMap()
            else
                response.headers.orEmpty().entries.fold(emptyMap<String, Map<String, String>>()) { acc, (headerName, header) ->
                    extractParameterExamples(header.examples, headerName, acc)
                }

        if (response.content == null || response.content.isEmpty()) {
            val responsePattern =
                HttpResponsePattern(
                    headersPattern = HttpHeadersPattern(headersMap.mapValues { it.value.first }),
                    body = NoBodyPattern,
                    status = status.toIntOrNull() ?: DEFAULT_RESPONSE_CODE,
                )

            val examples =
                headerExamples.mapNotNull { (exampleName, headerExamples) ->
                    val intStatus = status.toIntOrNull()?.takeIf { it != 0 } ?: return@mapNotNull null

                    exampleName to
                        HttpResponse(
                            intStatus,
                            body = NoBodyValue,
                            headers = headerExamples,
                        )
                }.toMap()

            return listOf(ResponsePatternData(response, MediaType(), responsePattern, examples))
        }

        val contentTypeHeader = headersMap.entries.find { it.key.lowercase() in listOf("content-type", "content-type?") }?.value
        val contentContext = collectorContext.at("content")
        return response.content.map { (contentType, mediaType) ->
            logger.debug("Processing response with content type $contentType")
            val mediaTypeContext = contentContext.at(contentType)
            val actualContentType = if (contentTypeHeader != null) {
                val (pattern, context) = contentTypeHeader
                getAndLogActualContentTypeHeader(pattern, contentType, context) ?: contentType
            } else {
                contentType
            }

            val responsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(headersMap.mapValues { it.value.first }, contentType = contentType),
                status = if (status == "default") 1000 else status.toInt(),
                body = when (contentType) {
                    "application/xml" -> toXMLPattern(mediaType, mediaTypeContext)
                    else -> toSpecmaticPattern(
                        mediaType = mediaType,
                        contentType = contentType,
                        collectorContext = mediaTypeContext,
                    )
                }
            )

            val exampleBodies: Map<String, String?> =
                if (specmaticConfig.getIgnoreInlineExamples() || getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES))
                    emptyMap()
                else
                    mediaType.examples?.mapValues {
                        resolveExample(it.value)?.value?.toString() ?: ""
                    } ?: emptyMap()

            val examples: Map<String, HttpResponse> =
                when (status.toIntOrNull()) {
                    0, null -> emptyMap()
                    else -> exampleBodies.map {
                        val mappedHeaderExamples = headerExamples[it.key]?.let { headerExample ->
                            if(headerExample.entries.find { contentType -> contentType.key.lowercase() == "content-type" } == null)
                                headerExample.plus(CONTENT_TYPE to actualContentType)
                            else
                                headerExample
                        } ?: mapOf(CONTENT_TYPE to actualContentType)

                        it.key to HttpResponse(
                            status.toInt(),
                            body = it.value ?: "",
                            headers = mappedHeaderExamples
                        )
                    }.toMap()
                }

            ResponsePatternData(response, mediaType, responsePattern, examples)
        }
    }

    private fun parseOperationSecuritySchemas(operation: Operation, securitySchemeComponents: Map<String, OpenAPISecurityScheme>, collectorContext: CollectorContext): List<OpenAPISecurityScheme> {
        logger.debug("Associating security schemes")
        val (securitySchemes, baseCollectorContext) = if (operation.security != null) {
            operation.security to collectorContext.at("security")
        } else {
            parsedOpenApi.security to collectorContext.withPath("security")
        }

        if (securitySchemes.isNullOrEmpty()) return listOf(NoSecurityScheme())
        fun getSecurityScheme(name: String, collectorContext: CollectorContext): OpenAPISecurityScheme? {
            return collectorContext.checkOptional<OpenAPISecurityScheme?>(name = name, value = securitySchemeComponents[name], isValid = { it != null })
                .violation { OpenApiLintViolations.SECURITY_SCHEME_MISSING }
                .message {
                    "Security scheme named \"$name\" was used, but no such security scheme has been defined in the spec. Either drop the security scheme, or add a definition to the spec."
                }
                .orUse { null }
                .build()
        }

        return securitySchemes.withIndex().mapNotNull { (index, securityScheme) ->
            val indexedCollectorContext = baseCollectorContext.at(index)
            when (securityScheme.keys.size) {
                0 -> NoSecurityScheme()
                1 -> getSecurityScheme(securityScheme.keys.single(), indexedCollectorContext)
                else -> CompositeSecurityScheme(securityScheme.keys.mapNotNull { getSecurityScheme(it, indexedCollectorContext) })
            }
        }
    }

    private fun toHttpRequestPatterns(
        httpPathPattern: HttpPathPattern,
        httpQueryParamPattern: HttpQueryParamPattern,
        httpMethod: String,
        operation: Operation,
        collectorContext: CollectorContext
    ): List<RequestPatternsData> {
        logger.debug("Processing requests for $httpMethod")
        val securitySchemeEntries = parsedOpenApi.components?.securitySchemes.orEmpty()
        val securitySchemeComponents = securitySchemeEntries.mapNotNull { (schemeName, scheme) ->
            val schemeContext = collectorContext.withPath("components").at("securitySchemes").at(schemeName)
            toSecurityScheme(schemeName, scheme, schemeContext)?.let { schemeName to it }
        }.toMap()

        val securitySchemesForRequestPattern = parseOperationSecuritySchemas(operation, securitySchemeComponents, collectorContext)
        val parameters = operation.parameters.orEmpty()
        validateSecuritySchemeParameterDuplication(
            securitySchemes = securitySchemesForRequestPattern,
            parameters = parameters.safeFilter<Parameter>(collectorContext),
            collectorContext = collectorContext
        )

        val headersMap = parameters.safeFilter<HeaderParameter>(collectorContext).associate { (index, parameter) ->
            logger.debug("Processing request header ${parameter.name}")
            val headerParamScope = collectorContext.at("parameters").at(index)
            toSpecmaticParamName(parameter.required != true, parameter.name) to Pair(
                first = toSpecmaticPattern(schema = parameter.schema, typeStack = emptyList(), collectorContext = headerParamScope.at("schema")),
                second = headerParamScope
            )
        }

        val contentTypeHeader = headersMap.entries.find { it.key.lowercase() in listOf("content-type", "content-type?") }?.value
        val headersPattern = HttpHeadersPattern(headersMap.mapValues { it.value.first })
        val requestPattern = HttpRequestPattern(
            httpPathPattern = httpPathPattern,
            httpQueryParamPattern = httpQueryParamPattern,
            method = httpMethod,
            headersPattern = headersPattern,
            securitySchemes = securitySchemesForRequestPattern
        )

        val exampleQueryParams = namedExampleParams<QueryParameter>(operation)
        val examplePathParams = namedExampleParams<PathParameter>(operation)
        val exampleHeaderParams = namedExampleParams<HeaderParameter>(operation)

        val exampleRequestBuilder = ExampleRequestBuilder(
            examplePathParams,
            exampleHeaderParams,
            exampleQueryParams,
            httpPathPattern,
            httpMethod,
            securitySchemesForRequestPattern
        )

        val (requestBody, requestBodyContext) = resolveRequestBody(operation, collectorContext) ?: return listOf(
            RequestPatternsData(
                requestPattern.copy(body = NoBodyPattern),
                exampleRequestBuilder.examplesBasedOnParameters
            )
        )

        if (requestBody.content == null || requestBody.content.isEmpty()) {
            val patternData = RequestPatternsData(requestPattern.copy(body = NoBodyPattern), exampleRequestBuilder.examplesBasedOnParameters)
            return listOf(patternData)
        }

        val contentContext = requestBodyContext.at("content")
        return requestBody.content.map { (contentType, mediaType) ->
            logger.debug("Processing request payload with media type $contentType")
            val mediaTypeContext = contentContext.at(contentType)
            when (contentType.lowercase()) {
                "multipart/form-data" -> {
                    val partSchemas = resolveSchemaIfRef(mediaType.schema, collectorContext = mediaTypeContext)
                    val parts: List<MultiPartFormDataPattern> =
                        partSchemas.resolvedSchema.properties.orEmpty().map { (partName, partSchema) ->
                            val partNameContext = partSchemas.collectorContext.at("properties").at(partName)
                            val partContentType = mediaType.encoding?.get(partName)?.contentType
                            val partNameWithPresence = if (partSchemas.resolvedSchema.required?.contains(partName) == true)
                                partName
                            else
                                "$partName?"

                            if (partSchema.isBinarySchema()) {
                                MultiPartFilePattern(
                                    partNameWithPresence,
                                    toSpecmaticPattern(partSchema, emptyList(), collectorContext = partNameContext),
                                    partContentType
                                )
                            } else {
                                MultiPartContentPattern(
                                    partNameWithPresence,
                                    toSpecmaticPattern(partSchema, emptyList(), collectorContext = partNameContext),
                                    partContentType
                                )
                            }
                        }

                    Pair(
                        requestPattern.copy(
                            multiPartFormDataPattern = parts,
                            headersPattern = headersPatternWithContentType(requestPattern, contentType)
                        ), emptyMap()
                    )
                }

                "application/x-www-form-urlencoded" -> Pair(
                    requestPattern.copy(
                        formFieldsPattern = toFormFields(mediaType, mediaTypeContext),
                        headersPattern = headersPatternWithContentType(requestPattern, contentType)
                    ), emptyMap()
                )

                "application/xml" -> Pair(
                    requestPattern.copy(
                        body = toXMLPattern(mediaType, collectorContext = mediaTypeContext),
                        headersPattern = headersPatternWithContentType(requestPattern, contentType)
                    ), emptyMap()
                )

                else -> {
                    val actualContentType = if (contentTypeHeader != null) {
                        val (pattern, context) = contentTypeHeader
                        getAndLogActualContentTypeHeader(pattern, contentType, context) ?: contentType
                    } else {
                        contentType
                    }

                    val examplesFromMediaType = mediaType.examples ?: emptyMap()
                    val exampleBodies: Map<String, String?> = examplesFromMediaType.mapValues {
                        resolveExample(it.value)?.value?.toString() ?: ""
                    }

                    val allExamples =
                        if (specmaticConfig.getIgnoreInlineExamples() || getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES))
                            emptyMap()
                        else
                            exampleRequestBuilder.examplesWithRequestBodies(exampleBodies, actualContentType)

                    val bodyIsRequired: Boolean = requestBody.required ?: true

                    val body = toSpecmaticPattern(mediaType, contentType = contentType, collectorContext = mediaTypeContext).let {
                        if (bodyIsRequired)
                            it
                        else
                            OptionalBodyPattern.fromPattern(it)
                    }

                    Pair(
                        requestPattern.copy(
                            body = body,
                            headersPattern = headersPatternWithContentType(requestPattern, contentType)
                        ), allExamples
                    )
                }
            }.let { RequestPatternsData(it.first, it.second, Pair(contentType, mediaType)) }
        }
    }

    private fun getAndLogActualContentTypeHeader(contentTypeHeaderPattern: Pattern, contentType: String?, collectorContext: CollectorContext): String? {
        val warning = "Content-Type should not be declared as a header per OAS standards"
        collectorContext.record(warning, isWarning = true, ruleViolation = OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN)

        val concretePattern = resolvedHop(contentTypeHeaderPattern, Resolver(newPatterns = patterns))
        try {
            val generated1 = concretePattern.generate(Resolver()).toStringLiteral()
            val generated2 = concretePattern.generate(Resolver()).toStringLiteral()
            if (generated1 == generated2 && generated1 != contentType) {
                collectorContext.record(
                    "Content-Type was declared in the spec, and will be ignored. Please remove it.",
                    isWarning = true,
                    ruleViolation = OpenApiLintViolations.MEDIA_TYPE_OVERRIDDEN
                )
                return generated1
            }
        } catch (_: ContractException) {
            // if an exception was thrown, we probably can't do the validation
        }

        return contentType
    }

    private fun headersPatternWithContentType(
        requestPattern: HttpRequestPattern,
        contentType: String
    ) = requestPattern.headersPattern.copy(
        contentType = contentType
    )

    private inline fun <reified T : Parameter> namedExampleParams(operation: Operation): Map<String, Map<String, String>> {
        if (specmaticConfig.getIgnoreInlineExamples() || getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES))
            return emptyMap()

        return operation.parameters.orEmpty().safeFilter<T>(CollectorContext()).map { it.value }.fold(emptyMap()) { acc, parameter ->
            extractParameterExamples(parameter.examples, parameter.name, acc)
        }
    }

    private fun extractParameterExamples(
        examplesToAdd: Map<String, Example>?,
        parameterName: String,
        examplesAccumulatedSoFar: Map<String, Map<String, String>>
    ): Map<String, Map<String, String>> {
        return examplesToAdd.orEmpty()
            .entries.filter { it.value.value?.toString().orEmpty() !in OMIT }
            .fold(examplesAccumulatedSoFar) { acc, (exampleName, example) ->
                val exampleValue = resolveExample(example)?.value?.toString() ?: ""
                val exampleMap = acc[exampleName] ?: emptyMap()
                acc.plus(exampleName to exampleMap.plus(parameterName to exampleValue))
            }
    }

    private fun resolveRequestBody(operation: Operation, collectorContext: CollectorContext): Pair<RequestBody, CollectorContext>? {
        return operation.requestBody?.`$ref`?.let {
            resolveReferenceToRequestBody(it, collectorContext.at("requestBody"))
        } ?: operation.requestBody?.let {
            it to collectorContext.at("requestBody")
        }
    }

    private fun toSecurityScheme(schemeName: String, securityScheme: SecurityScheme, collectorContext: CollectorContext): OpenAPISecurityScheme? {
        val securitySchemeConfiguration = securityConfiguration?.getOpenAPISecurityScheme(schemeName)
        if (securityScheme.scheme == BEARER_SECURITY_SCHEME) {
            return toBearerSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.OAUTH2) {
            return toBearerSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.APIKEY) {
            val apiKey = getSecurityTokenForApiKeyScheme(securitySchemeConfiguration, schemeName)
            if (securityScheme.`in` == SecurityScheme.In.HEADER)
                return APIKeyInHeaderSecurityScheme(name = securityScheme.name, apiKey = apiKey, schemeName = schemeName)
            if (securityScheme.`in` == SecurityScheme.In.QUERY)
                return APIKeyInQueryParamSecurityScheme(name = securityScheme.name, apiKey = apiKey, schemeName = schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.HTTP && securityScheme.scheme == "basic") {
            return toBasicAuthSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        collectorContext.record(
            message = "Specmatic currently supports oauth2, bearer, and api key authentication. Other security schemes will be ignored. Please reach out to the Specmatic team if you need support for this feature.",
            ruleViolation = OpenApiLintViolations.UNSUPPORTED_FEATURE
        )
        return null
    }

    private fun toBearerSecurityScheme(
        securitySchemeConfiguration: SecuritySchemeConfiguration?,
        schemeName: String,
    ): BearerSecurityScheme {
        val token = getSecurityTokenForBearerScheme(securitySchemeConfiguration, schemeName)
        return BearerSecurityScheme(configuredToken = token, schemeName = schemeName)
    }

    private fun toBasicAuthSecurityScheme(
        securitySchemeConfiguration: SecuritySchemeConfiguration?,
        schemeName: String,
    ): BasicAuthSecurityScheme {
        val token = getSecurityTokenForBasicAuthScheme(securitySchemeConfiguration, schemeName)
        return BasicAuthSecurityScheme(token = token, schemeName = schemeName)
    }

    private fun toFormFields(mediaType: MediaType, collectorContext: CollectorContext): Map<String, Pattern> {
        val resolvedSchema = resolveSchemaIfRef(mediaType.schema, collectorContext = collectorContext)
        return resolvedSchema.resolvedSchema.properties.orEmpty().map { (formFieldName, formFieldValue) ->
            val formFieldContext = resolvedSchema.collectorContext.at("properties").at(formFieldName)
            formFieldName to toSpecmaticPattern(
                schema = formFieldValue, typeStack = emptyList(),
                jsonInFormData = isJsonInString(mediaType, formFieldName),
                collectorContext = formFieldContext
            )
        }.toMap()
    }

    private fun isJsonInString(
        mediaType: MediaType, formFieldName: String?
    ) = if (mediaType.encoding.isNullOrEmpty()) false
    else mediaType.encoding[formFieldName]?.contentType == "application/json"

    private fun List<Schema<Any>>.impliedDiscriminatorMappings(): Map<String, String> {
        return this.filter { it.`$ref` != null }.associate {
            val dataTypeName = it.`$ref`.split("/").last()
            val targetSchema = it.`$ref`
            dataTypeName to targetSchema
        }
    }

    private fun Map<String, String>.distinctByValue(): Map<String, String> {
        return this.entries.distinctBy { it.value }.associate { it.key to it.value }
    }

    private fun toSpecmaticPattern(mediaType: MediaType, jsonInFormData: Boolean = false, collectorContext: CollectorContext, contentType: String = ""): Pattern {
        val defaultSchemaDecision by lazy(LazyThreadSafetyMode.NONE) {
            when {
                contentType.contains("json", true) || contentType.contains("form-data", true) -> ObjectSchema().apply { additionalProperties = true } to "free-form object"
                contentType.contains("text", true) || contentType.contains("xml", true) -> StringSchema() to "string"
                else -> BinarySchema() to "binary"
            }
        }

        val resolvedSchema = collectorContext.requirePojo(
            isWarning = true,
            message = { "No schema property defined under mediaType $contentType, defaulting to ${defaultSchemaDecision.second}" },
            extract = { mediaType.schema },
            createDefault = { defaultSchemaDecision.first }
        )

        return toSpecmaticPattern(
            schema = resolvedSchema,
            typeStack = emptyList(),
            jsonInFormData = jsonInFormData,
            collectorContext = collectorContext.at("schema")
        )
    }

    private fun resolveDeepAllOfs(schema: Schema<*>, discriminatorDetails: DiscriminatorDetails, typeStack: Set<String>, topLevel: Boolean, collectorContext: CollectorContext): Pair<List<DeepAllOfSchema>, DiscriminatorDetails> {
        // Pair<String [property name], Map<String [possible value], Pair<String [Schema name derived from the ref], Schema<Any> [reffed schema]>>>
        val newDiscriminatorDetailsDetails: Triple<String, Map<String, Pair<String, List<DeepAllOfSchema>>>, DiscriminatorDetails>? = if (!topLevel) null else schema.discriminator?.let { rawDiscriminator ->
            rawDiscriminator.propertyName?.let { propertyName ->
                val mapping = rawDiscriminator.mapping ?: emptyMap()

                validateMappings(mapping, collectorContext)
                val mappingWithSchemaListAndDiscriminator =
                    mapping.entries.mapNotNull { (discriminatorValue, refPath) ->
                        val (mappedSchemaName, mappedSchema) = resolveReferenceToSchema(refPath, collectorContext)
                        val mappedComponentName = extractComponentName(refPath, collectorContext)
                        if (mappedComponentName !in typeStack) {
                            val value = mappedSchemaName to resolveDeepAllOfs(
                                mappedSchema,
                                discriminatorDetails,
                                typeStack + mappedComponentName,
                                topLevel = false,
                                collectorContext = collectorContext,
                            )
                            discriminatorValue to value
                        } else {
                            null
                        }
                    }.toMap()

                val discriminatorsFromResolvedMappingSchemas = mappingWithSchemaListAndDiscriminator.values.map { (_, discriminator) ->
                    discriminator.second
                }

                val mergedDiscriminatorDetailsFromMappingSchemas = discriminatorsFromResolvedMappingSchemas.fold(DiscriminatorDetails()) { acc, discriminator ->
                    acc.plus(discriminator)
                }

                val mappingWithSchema: Map<String, Pair<String, List<DeepAllOfSchema>>> = mappingWithSchemaListAndDiscriminator.mapValues { entry: Map.Entry<String, Pair<String, Pair<List<DeepAllOfSchema>, DiscriminatorDetails>>> ->
                    entry.value.first to (entry.value.second.first)
                }

                Triple(propertyName, mappingWithSchema, mergedDiscriminatorDetailsFromMappingSchemas)
            }
        }

        val schemasToProcess = schema.allOf.orEmpty().withIndex().map {
            Pair(collectorContext.at("allOf").at(it.index), it.value)
        }.plus(
            Pair(first = collectorContext, second = schema)
        )

        val allOfs = schemasToProcess.mapNotNull { (schemaContext, constituentSchema) ->
            if (constituentSchema.`$ref` == null) {
                return@mapNotNull listOf(DeepAllOfSchema(constituentSchema, patternName = null, schemaContext)) to discriminatorDetails
            }

            val resolvedRefDetails = resolveSchemaIfRef(constituentSchema, collectorContext = schemaContext)
            val refEntry = DeepAllOfSchema(resolvedRefDetails.resolvedSchema, resolvedRefDetails.componentName, resolvedRefDetails.collectorContext)
            if (resolvedRefDetails.componentName in typeStack) return@mapNotNull null
            resolveDeepAllOfs(
                resolvedRefDetails.resolvedSchema,
                discriminatorDetails.plus(newDiscriminatorDetailsDetails),
                typeStack + resolvedRefDetails.componentName,
                topLevel = false,
                collectorContext = resolvedRefDetails.collectorContext
            )
        }

        val discriminatorDetailsForThisLevel = newDiscriminatorDetailsDetails?.let {
            DiscriminatorDetails().plus(it)
        } ?: DiscriminatorDetails()

        return allOfs.fold(Pair(emptyList(), discriminatorDetailsForThisLevel)) { acc, item ->
            val (accSchemas, accDiscriminator) = acc
            val (additionalSchemas, additionalSchemasDiscriminator) = item
            accSchemas.plus(additionalSchemas) to accDiscriminator.plus(additionalSchemasDiscriminator)
        }
    }

    private fun toSpecmaticPattern(schema: Schema<*>?, typeStack: List<String>, patternName: String = "", jsonInFormData: Boolean = false, collectorContext: CollectorContext): Pattern {
        val debugMessage =
            if (patternName.isNotBlank() && collectorContext.hasPath) {
                "Processing schema $patternName at ${collectorContext.path}"
            } else if (patternName.isNotBlank()) {
                "Processing schema $patternName"
            } else if (collectorContext.hasPath) {
                "Processing schema at ${collectorContext.path}"
            } else {
                "Processing inline schema"
            }

        logger.debug(debugMessage)

        if (patternName.isNotBlank()) {
            val preExistingResult = patterns["($patternName)"]
            if (preExistingResult != null) return preExistingResult
            if (typeStack.filter { it == patternName }.size > 1) return DeferredPattern("($patternName)")
        }

        val schemaToProcess = collectorContext.requirePojo(
            message = { "No schema found. Please add the missing schema." },
            extract = { schema },
            createDefault = { Schema<Any>() }
        )

        val pattern = schemaToProcess.toSpecmaticPattern(patternName, typeStack, collectorContext)
        val withFormData = if (pattern.instanceOf(JSONObjectPattern::class) && jsonInFormData) {
            PatternInStringPattern(patterns.getOrDefault("($patternName)", StringPattern()), "($patternName)")
        } else {
            pattern
        }

        return when (schemaToProcess.isNullable()) {
            false -> withFormData
            true -> withFormData.toNullable(schemaToProcess.extractFirstExampleAsString())
        }
    }

    private fun handleMultiType(schema: Schema<*>, collectorContext: CollectorContext, typeStack: List<String>, patternName: String, types: List<String>, example: String? = null): Pattern {
        val typeContext = collectorContext.at("type")
        val patterns = types.mapIndexed { index, singleType ->
            val indexTypeContext = typeContext.at(index)
            val singleTypeSchema = SchemaUtils.cloneWithType(schema, singleType)
            toSpecmaticPattern(singleTypeSchema, typeStack, collectorContext = indexTypeContext)
        }
        return AnyOfPattern(pattern = patterns, typeAlias = "($patternName)", example = example)
    }

    private fun handleAllOf(schema: Schema<*>, typeStack: List<String>, patternName: String, collectorContext: CollectorContext): Pattern {
        val (deepListOfAllOfs, allDiscriminators) = resolveDeepAllOfs(schema, DiscriminatorDetails(), emptySet(), topLevel = true, collectorContext = collectorContext)
        val explodedDiscriminators = allDiscriminators.explode()
        val topLevelRequired = schema.required.orEmpty()

        val schemaProperties = explodedDiscriminators.map { discriminator ->
            val schemasFromDiscriminator = discriminator.schemas
            (deepListOfAllOfs + schemasFromDiscriminator).map { (schemaToProcess, schemaPatternName, schemaCollectorContext) ->
                val requiredFields = topLevelRequired.plus(schemaToProcess.required.orEmpty())
                SchemaProperty(
                    extensions = schemaToProcess.extensions.orEmpty(),
                    properties = toSchemaProperties(
                        schema = schemaToProcess,
                        requiredFields = requiredFields.distinct(),
                        patternName = schemaPatternName ?: patternName,
                        typeStack = typeStack,
                        discriminatorDetails = discriminator,
                        collectorContext = schemaCollectorContext,
                    )
                )
            }.fold(SchemaProperty(extensions = schema.extensions.orEmpty(), properties = emptyMap())) { propertiesAcc, propertiesEntry ->
                val (extensions, properties) = propertiesEntry
                propertiesAcc.copy(
                    extensions = propertiesAcc.extensions.plus(extensions),
                    properties = combine(properties, propertiesAcc.properties)
                )
            }
        }

        val schemasWithOneOf = deepListOfAllOfs.filter { it.schema.oneOf != null }
        val oneOfs = schemasWithOneOf.map { (schemaToProcess, schemaPatternName, schemaCollectorContext) ->
            schemaToProcess.oneOf.mapIndexed { index, schema ->
                val indexContext = schemaCollectorContext.at("oneOf").at(index)
                val resolvedRef = resolveSchemaIfRef(schema, schemaPatternName, indexContext)
                val requiredFields = resolvedRef.resolvedSchema.required.orEmpty().plus(topLevelRequired)
                resolvedRef.componentName to SchemaProperty(
                    resolvedRef.resolvedSchema.extensions.orEmpty(), toSchemaProperties(
                        schema = resolvedRef.resolvedSchema,
                        requiredFields = requiredFields,
                        patternName = resolvedRef.componentName,
                        typeStack = typeStack,
                        collectorContext = resolvedRef.collectorContext,
                    )
                )
            }.flatMap { (componentName, schemaProperty) ->
                schemaProperties.map {
                    componentName to SchemaProperty(
                        extensions = it.extensions.plus(schemaProperty.extensions),
                        properties = combine(it.properties, schemaProperty.properties)
                    )
                }
            }
        }.flatten().map { (componentName, schemaProperty) ->
            toJSONObjectPattern(schemaProperty.properties, "(${componentName})", schemaProperty.extensions)
        }

        return when {
            oneOfs.isNotEmpty() -> {
                oneOfs.toPatternOrAny(typeAlias = "($patternName)")
            }
            allDiscriminators.isNotEmpty() -> {
                createDiscriminatorPattern(schema, schemaProperties, allDiscriminators, patternName)
            }
            else -> {
                createObjectPattern(schemaProperties, patternName)
            }
        }.let {
            cacheComponentPattern(patternName, it)
        }
    }

    private fun resolveSchemaIfRef(schema: Schema<*>?, patternName: String? = null, collectorContext: CollectorContext): ResolvedRef {
        val schemaToProcess = collectorContext.requirePojo(
            message = { "No schema found. Please add the missing schema." },
            extract = { schema },
            createDefault = { Schema<Any>() }
        )

        if (schemaToProcess.`$ref` == null) return ResolvedRef(patternName.orEmpty(), schemaToProcess, schemaToProcess, collectorContext)
        return resolveSchema(schemaToProcess, collectorContext)
    }

    private fun resolveSchemaIfRefElseAtSchema(schema: Schema<*>, collectorContext: CollectorContext): Pair<Schema<*>, CollectorContext> {
        val schemaToProcess = collectorContext.requirePojo(
            message = { "No schema found. Please add the missing schema." },
            extract = { schema },
            createDefault = { Schema<Any>() }
        )

        if (schemaToProcess.`$ref` == null) return Pair(schemaToProcess, collectorContext.at("schema"))
        return resolveSchema(schemaToProcess, collectorContext.at("schema")).let { Pair(it.resolvedSchema, it.collectorContext) }
    }

    private fun List<Pattern>.toPatternOrAny(typeAlias: String? = null): Pattern {
        return singleOrNull() ?: AnyPattern(this, typeAlias = typeAlias, extensions = emptyMap())
    }

    private fun createDiscriminatorPattern(schema: Schema<*>, properties: List<SchemaProperty>, discriminators: DiscriminatorDetails, aliasName: String): AnyPattern {
        val patterns = properties.zip(discriminators.schemaNames).map { (prop, schemaName) ->
            toJSONObjectPattern(prop.properties, "($schemaName)", prop.extensions)
        }

        val discriminatorObj = Discriminator.create(
            discriminators.key,
            discriminators.values.toSet(),
            schema.discriminator?.mapping.orEmpty()
        )

        return AnyPattern(pattern = patterns, discriminator = discriminatorObj, typeAlias = "($aliasName)")
    }

    private fun createObjectPattern(properties: List<SchemaProperty>, aliasName: String): Pattern {
        val patterns = properties.map { prop ->
            toJSONObjectPattern(prop.properties, "($aliasName)", prop.extensions)
        }

        return patterns.singleOrNull() ?: AnyPattern(patterns, extensions = emptyMap())
    }

    private fun handleOneOf(schema: Schema<*>, typeStack: List<String>, patternName: String, collectorContext: CollectorContext): Pattern {
        val oneOfContext = collectorContext.at("oneOf")
        val candidatePatterns = schema.oneOf.withIndex().mapNotNull { (index, schema) ->
            val oneOfIndexContext = oneOfContext.at(index)
            if (nullableEmptyObject(schema)) return@mapNotNull null
            toSpecmaticPattern(schema = schema, typeStack = typeStack, collectorContext = oneOfIndexContext)
        }

        val nullable = if (schema.oneOf.any { nullableEmptyObject(it) }) listOf(NullPattern) else emptyList()
        val impliedDiscriminatorMappings = schema.oneOf.impliedDiscriminatorMappings()
        val finalDiscriminatorMappings = schema.discriminator?.mapping.orEmpty().plus(impliedDiscriminatorMappings).distinctByValue()
        validateMappings(finalDiscriminatorMappings, collectorContext)

        return AnyPattern(
            candidatePatterns.plus(nullable),
            typeAlias = "(${patternName})",
            discriminator = Discriminator.create(
                schema.discriminator?.propertyName,
                finalDiscriminatorMappings.keys.toSet(),
                finalDiscriminatorMappings,
            ),
        ).let {
            cacheComponentPattern(patternName, it)
        }
    }

    private fun handleAnyOf(schema: Schema<*>, typeStack: List<String>, patternName: String, collectorContext: CollectorContext): Pattern {
        val anyOfContext = collectorContext.at("anyOf")
        val candidatePatterns = schema.anyOf.mapIndexed { index, subSchema ->
            val anyOfIndexContext = anyOfContext.at(index)
            toSpecmaticPattern(schema = subSchema, typeStack = typeStack, collectorContext = anyOfIndexContext)
        }

        val impliedDiscriminatorMappings = schema.anyOf.impliedDiscriminatorMappings()
        val finalDiscriminatorMappings = schema.discriminator?.mapping.orEmpty().plus(impliedDiscriminatorMappings).distinctByValue()
        validateMappings(finalDiscriminatorMappings, collectorContext)

        return AnyOfPattern(
            pattern = ensureAllObjectPatternsHaveAdditionalProperties(candidatePatterns),
            typeAlias = "($patternName)",
            discriminator = Discriminator.create(
                schema.discriminator?.propertyName,
                finalDiscriminatorMappings.keys.toSet(),
                finalDiscriminatorMappings,
            ),
        ).let {
            cacheComponentPattern(patternName, it)
        }
    }

    private fun validateMappings(mappings: Map<String, String>, collectorContext: CollectorContext) {
        val components = parsedOpenApi.components ?: Components()
        val schemas = components.schemas.orEmpty()
        mappings.forEach { (discriminatorValue, refPath) ->
            val componentName = extractComponentName(refPath, collectorContext)
            collectorContext.at("discriminator").at("mapping").at(discriminatorValue).requirePojo<Schema<*>?>(
                message = {
                    "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"$componentName\"."
                },
                extract = { schemas[componentName] },
                createDefault = { null },
                ruleViolation = { OpenApiLintViolations.UNRESOLVED_REFERENCE }
            )
        }
    }

    private fun ensureAllObjectPatternsHaveAdditionalProperties(patterns: List<Pattern>): List<Pattern> {
        val resolver = Resolver(newPatterns = this@OpenApiSpecification.patterns)
        return patterns.map { pattern ->
            if (pattern !is PossibleJsonObjectPatternContainer) return@map pattern
            pattern.ensureAdditionalProperties(resolver)
        }
    }

    private data class ResolvedRef(val componentName: String, val resolvedSchema: Schema<*>, val referredSchema: Schema<*>, val collectorContext: CollectorContext)
    private fun resolveSchema(schema: Schema<*>, collectorContext: CollectorContext): ResolvedRef {
        val (componentName, referredSchema) = resolveReferenceToSchema(schema.`$ref`, collectorContext)
        val resolvedSchema = if (parsedOpenApi.specVersion == SpecVersion.V31) {
            mergeResolvedIfJsonSchema(referredSchema, schema)
        } else {
            referredSchema
        }

        collectorContext.checkPojo(
            value = schema,
            message = {
                "This reference has sibling properties. In accordance with the OpenAPI 3.0 standard, they will be ignored. Please remove them."
            },
            isValid = { parsedOpenApi.specVersion == SpecVersion.V31 || it.type == null },
            createDefault = { it },
            ruleViolation = { OpenApiLintViolations.REF_HAS_SIBLINGS },
            isWarning = true
        )

        return ResolvedRef(componentName, resolvedSchema, referredSchema, collectorContext.withPath("components").at("schemas").at(componentName))
    }

    private fun convertAndCacheResolvedRef(
        resolvedRef: ResolvedRef,
        typeStack: List<String>,
        patternName: String,
        build: (Schema<*>, List<String>, String, Boolean, CollectorContext) -> Pattern,
        collectorContext: CollectorContext
    ): Pattern {
        val cacheKey = if (resolvedRef.resolvedSchema != resolvedRef.referredSchema) {
            patternName.takeUnless(String::isBlank)
        } else {
            resolvedRef.componentName
        }

        if (cacheKey == null) return build(resolvedRef.resolvedSchema, typeStack, patternName, false, collectorContext)
        if (typeStack.contains(cacheKey)) return DeferredPattern("($cacheKey)")
        val refCollector = collectorContext.withPath("components/schemas/$cacheKey")
        val builtPattern = build(resolvedRef.resolvedSchema, typeStack.plus(cacheKey), cacheKey, false, refCollector)
        cacheComponentPattern(cacheKey, builtPattern)
        return DeferredPattern("($cacheKey)")
    }

    private fun handleReference(schema: Schema<*>, typeStack: List<String>, patternName: String, collectorContext: CollectorContext): Pattern {
        val resolvedRef = resolveSchema(schema, collectorContext)
        return convertAndCacheResolvedRef(
            resolvedRef = resolvedRef,
            typeStack = typeStack,
            patternName = patternName,
            collectorContext = collectorContext,
            build = ::toSpecmaticPattern
        )
    }

    private fun handleXmlReference(schema: Schema<*>, typeStack: List<String>, patternName: String?, collectorContext: CollectorContext): Pair<String, Pattern> {
        val resolvedRef = resolveSchema(schema, collectorContext)
        val nodeName = resolvedRef.resolvedSchema.xml?.name ?: patternName ?: resolvedRef.componentName
        val pattern = convertAndCacheResolvedRef(
            resolvedRef = resolvedRef,
            typeStack = typeStack,
            patternName = patternName.orEmpty(),
            collectorContext = collectorContext,
            build = { resolvedSchema, newTypeStack, componentName, _, refCollector ->
                toXMLPattern(resolvedSchema, componentName, newTypeStack, refCollector)
            }
        )

        return Pair(nodeName, pattern)
    }

    private fun exactPattern(value: Value, patterName: String): ExactValuePattern {
        return ExactValuePattern(pattern = value, typeAlias = patterName)
    }

    private fun stringPattern(schema: Schema<*>, patternName: String, collectorContext: CollectorContext, example: String? = null): StringPattern {
        val stringConstraints = StringConstraints(schema, patternName, collectorContext)
        return StringPattern.from(stringConstraints, schema.pattern, example, collectorContext)
    }

    private fun numberPattern(schema: Schema<*>, collectorContext: CollectorContext, isDoubleFormat: Boolean, example: String?) : NumberPattern {
        val minSource = if (schema.exclusiveMinimumValue != null) NumericBoundSource.EXCLUSIVE_MINIMUM else if (schema.minimum != null) NumericBoundSource.MINIMUM else null
        val maxSource = if (schema.exclusiveMaximumValue != null) NumericBoundSource.EXCLUSIVE_MAXIMUM else if (schema.maximum != null) NumericBoundSource.MAXIMUM else null
        return NumberConstraints(
            example = example,
            minSource = minSource,
            maxSource = maxSource,
            isDoubleFormat = isDoubleFormat,
            minimum = schema.exclusiveMinimumValue ?: schema.minimum,
            maximum = schema.exclusiveMaximumValue ?: schema.maximum,
            isBoundaryTestingEnabled = isBoundaryTestingEnabled(schema),
            isExclusiveMinimum = (schema.exclusiveMinimumValue != null) || (schema.exclusiveMinimum == true),
            isExclusiveMaximum = (schema.exclusiveMaximumValue != null) || (schema.exclusiveMaximum == true),
        ).toPattern(collectorContext)
    }

    private fun enumPattern(schema: Schema<*>, patternName: String, types: List<String>, collectorContext: CollectorContext, example: String? = null): EnumPattern {
        val enumDataTypes = types.sortedWith(compareBy { it == "string" }).map(::withPatternDelimiters)
        val converter: (Any) -> Value = { value ->
            enumDataTypes.firstNotNullOfOrNull {
                val pattern = builtInPatterns[it] ?: return@firstNotNullOfOrNull null
                runCatching { pattern.parse(value.toString(), Resolver()) }.getOrNull()
            } ?: run {
                logger.debug("Failed to convert enum value $value against provided list of types $enumDataTypes, defaulting to any scalar")
                parsedScalarValue(value.toString())
            }
        }

        val enumValuesPattern = toSpecmaticPattern(
            schema = SchemaUtils.modifyDeepCopySchema(schema) { it.apply { enum = null } },
            typeStack = emptyList(),
            collectorContext = CollectorContext()
        )

        return toEnum(
            schema = schema,
            pattern = enumValuesPattern,
            isNullable = schema.isNullable(),
            patternName = patternName,
            multiType = types.size > 1,
            collectorContext = collectorContext,
            toSpecmaticValue = converter
        ).withExample(example)
    }

    private fun isBoundaryTestingEnabled(schema: Schema<*>): Boolean {
        val extensions = schema.extensions.orEmpty()
        if (extensions.isEmpty()) return false

        val raw = extensions[X_SPECMATIC_HINT] ?: return false

        fun normalizeHintString(s: String): String = s.trim().lowercase()

        return when (raw) {
            is String -> raw
                .split(*HINT_VALUE_DELIMITERS)
                .map(::normalizeHintString)
                .any { it == HINT_BOUNDARY_TESTING_ENABLED }
            else -> false
        }
    }

    private fun toListExample(example: Any?): List<String?>? {
        if (example == null)
            return null

        if (example !is ArrayNode)
            return null

        return example.toList().flatMap {
            when {
                it.isNull -> listOf(null)
                it.isNumber -> listOf(it.numberValue().toString())
                it.isBoolean -> listOf(it.booleanValue().toString())
                it.isTextual -> listOf(it.textValue())
                else -> emptyList()
            }
        }
    }

    private fun combine(
        propertiesEntry: Map<String, Pattern>,
        propertiesAcc: Map<String, Pattern>
    ): Map<String, Pattern> {
        val updatedPropertiesAcc: Map<String, Pattern> =
            propertiesEntry.entries.fold(propertiesAcc) { acc, propertyEntry ->
                val existingPropertyValue = acc[propertyEntry.key.withOptionalSuffix()]
                    ?: acc[propertyEntry.key.withoutOptionalSuffix()]

                val newPropertyValue = if (existingPropertyValue != null)
                    restrictivePatternBetween(existingPropertyValue, propertyEntry.value)
                else propertyEntry.value

                when (val keyWithoutOptionality = withoutOptionality(propertyEntry.key)) {
                    in acc ->
                        acc.plus(propertyEntry.key to newPropertyValue)

                    propertyEntry.key ->
                        acc.minus("$keyWithoutOptionality?").plus(propertyEntry.key to newPropertyValue)

                    else ->
                        acc.plus(propertyEntry.key to newPropertyValue)
                }
            }

        return updatedPropertiesAcc
    }

    private fun restrictivePatternBetween(pattern1: Pattern, pattern2: Pattern): Pattern {
        return if (pattern1 !is AnythingPattern && pattern2 is AnythingPattern)
            pattern1
        else
            pattern2
    }

    private fun String.withOptionalSuffix(): String {
        if(this.endsWith("?")) return this
        return "$this?"
    }

    private fun String.withoutOptionalSuffix(): String {
        if(this.endsWith("?")) return this.removeSuffix("?")
        return this
    }

    private fun <T : Pattern> cacheComponentPattern(componentName: String, pattern: T): T {
        if (componentName.isNotBlank() && pattern !is DeferredPattern) {
            val typeName = "(${componentName})"
            val prev = patterns[typeName]
            if (pattern != prev) {
                if (prev != null) {
                    logger.debug("Replacing cached component pattern. name=$componentName, prev=$prev, new=$pattern")
                }
                patterns[typeName] = pattern
            }
        }
        return pattern
    }

    private fun nullableEmptyObject(schema: Schema<*>): Boolean {
        return schema.isSchema(OBJECT_TYPE, nullable = true, multi = false)
    }

    private fun toXMLPattern(mediaType: MediaType, collectorContext: CollectorContext): Pattern {
        return toXMLPattern(mediaType.schema, typeStack = emptyList(), collectorContext = collectorContext.at("schema"))
    }

    private fun toXMLPattern(schema: Schema<*>, nodeNameFromProperty: String? = null, typeStack: List<String>, collectorContext: CollectorContext): XMLPattern {
        val name = schema.xml?.name ?: nodeNameFromProperty
        return when {
            schema.isPrimitive() -> {
                name ?: throw ContractException("Could not determine name for an xml node")
                val primitivePattern = toSpecmaticPattern(schema, typeStack, collectorContext = collectorContext)
                XMLPattern(XMLTypeData(name, name, emptyMap(), listOf(primitivePattern)))
            }

            schema.isSchema(OBJECT_TYPE, multi = false) -> {
                if (schema.properties == null) {
                    throw ContractException("XML schema named $name does not have properties.")
                }

                val nodeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute != true
                }

                val nodes = nodeProperties.map { (propertyName: String, propertySchema) ->
                    val propertyContext = collectorContext.at(propertyName)
                    val type = toXMLPattern(propertySchema, propertyName, typeStack, propertyContext)
                    val optionalAttribute = if (propertyName !in (schema.required ?: emptyList<String>())) {
                        mapOf(OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(OPTIONAL_ATTRIBUTE_VALUE)))
                    } else {
                        emptyMap()
                    }
                    type.copy(pattern = type.pattern.copy(attributes = optionalAttribute.plus(type.pattern.attributes)))
                }

                val attributeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute == true
                }

                val attributes: Map<String, Pattern> = attributeProperties.map { (name, propertySchema) ->
                    val attributeContext = collectorContext.at(name)
                    val attributeName = if(name !in schema.required.orEmpty())
                        "$name.opt"
                    else
                        name
                    attributeName to toSpecmaticPattern(propertySchema, emptyList(), collectorContext = attributeContext)
                }.toMap()

                name ?: throw ContractException("Could not determine name for an xml node")
                val namespaceAttributes: Map<String, ExactValuePattern> = if (schema.xml?.namespace != null && schema.xml?.prefix != null) {
                    val attributeName = "xmlns:${schema.xml?.prefix}"
                    val attributeValue = ExactValuePattern(StringValue(schema.xml.namespace))
                    mapOf(attributeName to attributeValue)
                } else {
                    emptyMap()
                }

                val xmlTypeData = XMLTypeData(name, realName(schema, name), namespaceAttributes.plus(attributes), nodes)
                XMLPattern(xmlTypeData)
            }

            schema.isSchema(ARRAY_TYPE, multi = false) -> {
                val repeatingSchema = schema.items
                val itemName = repeatingSchema.xml?.name ?: nodeNameFromProperty
                val itemsContext = collectorContext.at("items")
                val innerPattern = toXMLPattern(repeatingSchema, itemName, typeStack, itemsContext).let { repeatingType ->
                    repeatingType.copy(
                        pattern = repeatingType.pattern.copy(
                            attributes = repeatingType.pattern.attributes.plus(
                                OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(MULTIPLE_ATTRIBUTE_VALUE))
                            )
                        )
                    )
                }

                if (schema.xml?.wrapped == true) {
                    val wrapperName = name ?: throw ContractException("Wrapped array must have a name")
                    XMLPattern(XMLTypeData(wrapperName, wrapperName, emptyMap(), listOf(innerPattern)))
                } else {
                    innerPattern
                }
            }

            else -> {
                if (schema.`$ref` == null) throw ContractException("Node not recognized as XML type: ${schema.type}")
                val (nodeName, deferredPattern) = handleXmlReference(schema = schema, typeStack = typeStack, patternName = name, collectorContext = collectorContext)
                val componentName = withoutPatternDelimiters(deferredPattern.typeAlias.orEmpty())
                XMLPattern(XMLTypeData(
                    nodeName,
                    nodeName,
                    mapOf(TYPE_ATTRIBUTE_NAME to ExactValuePattern(StringValue(componentName)))
                ))
            }
        }
    }

    private fun realName(schema: Schema<*>, name: String): String = if (schema.xml?.prefix != null) {
        "${schema.xml?.prefix}:${name}"
    } else {
        name
    }

    private fun toJsonObjectPattern(schema: Schema<*>, patternName: String, typeStack: List<String>, collectorContext: CollectorContext): JSONObjectPattern {
        val requiredFields = schema.required.orEmpty()
        val schemaProperties = toSchemaProperties(schema, requiredFields, patternName, typeStack, collectorContext = collectorContext)
        val minProperties: Int? = schema.minProperties
        val maxProperties: Int? = schema.maxProperties
        val jsonObjectPattern = toJSONObjectPattern(schemaProperties, if(patternName.isNotBlank()) "(${patternName})" else null).copy(
            minProperties = minProperties,
            maxProperties = maxProperties,
            additionalProperties = additionalPropertiesFrom(schema, patternName, typeStack, collectorContext),
            extensions = schema.extensions.orEmpty()
        )
        return cacheComponentPattern(patternName, jsonObjectPattern)
    }

    private fun additionalPropertiesFrom(schema: Schema<*>, patternName: String, typeStack: List<String>, collectorContext: CollectorContext): AdditionalProperties {
        val schemaProperties = schema.properties.orEmpty()

        val additionalProperties = schema.extractAdditionalProperties() ?: return when {
            schemaProperties.isEmpty() -> AdditionalProperties.FreeForm
            else -> AdditionalProperties.NoAdditionalProperties
        }

        val additionalPropertiesContext = collectorContext.at("additionalProperties")
        return when (additionalProperties) {
            true -> AdditionalProperties.FreeForm
            false -> AdditionalProperties.NoAdditionalProperties
            is Schema<*> -> processAdditionalPropertiesSchema(additionalProperties, typeStack, additionalPropertiesContext)
            else -> throw ContractException(
                breadCrumb = "$patternName.additionalProperties",
                errorMessage = "Unrecognized type for additionalProperties: expected a boolean or a schema"
            )
        }
    }

    private fun processAdditionalPropertiesSchema(schema: Schema<*>, typeStack: List<String>, collectorContext: CollectorContext): AdditionalProperties {
        val parsedPattern = toSpecmaticPattern(schema, typeStack, collectorContext = collectorContext)
        return if (parsedPattern is AnythingPattern) AdditionalProperties.FreeForm
        else AdditionalProperties.PatternConstrained(parsedPattern)
    }

    private fun toSchemaProperties(
        schema: Schema<*>,
        requiredFields: List<String>,
        patternName: String,
        typeStack: List<String>,
        discriminatorDetails: DiscriminatorDetails = DiscriminatorDetails(),
        collectorContext: CollectorContext
    ): Map<String, Pattern> {
        val propertiesContext = collectorContext.at("properties")
        val properties = schema.properties.orEmpty()
        val fixedRequiredFields = requiredFields.map { field ->
            collectorContext.at("required").check<String?>(value = field, isValid = { properties.contains(field) })
            .message { "Required property \"$field\" is not defined in properties, ignoring this requirement" }
            .orUse { null }
            .build(isWarning = true)
        }

        return schema.properties.orEmpty().map { (propertyName, propertyType) ->
            val propertyContext = propertiesContext.at(propertyName)
            if (schema.discriminator?.propertyName == propertyName)
                propertyName to ExactValuePattern(StringValue(patternName), discriminator = true)
            else if (discriminatorDetails.hasValueForKey(propertyName)) {
                propertyName to discriminatorDetails.valueFor(propertyName)
            } else {
                val optional = !fixedRequiredFields.contains(propertyName)
                toSpecmaticParamName(optional, propertyName) to toSpecmaticPattern(
                    schema = propertyType,
                    typeStack = typeStack,
                    collectorContext = propertyContext
                )
            }
        }.toMap()
    }

    private fun toEnum(schema: Schema<*>, pattern: Pattern, isNullable: Boolean, patternName: String, multiType: Boolean = false, collectorContext: CollectorContext, toSpecmaticValue: (Any) -> Value): EnumPattern {
        val specmaticValues = schema.enum.map { enumValue ->
            when (enumValue) {
                null -> NullValue
                else -> toSpecmaticValue(enumValue)
            }
        }

        if (parsedOpenApi.specVersion != SpecVersion.V31 && NullValue in specmaticValues && !isNullable && specmaticValues.size != 1) {
            collectorContext.at("enum").record(
                message = """
                Failed to parse enum. One or more enum values were parsed as null
                This often happens in OpenAPI 3.0.x when enum values have mixed or invalid types and the parser implicitly coerces those values to null
                Please check the enum schema and entries or mark then schema as nullable if this was intentional
                """.trimIndent()
            )
        }

        return EnumPattern.from(
            values = specmaticValues,
            pattern = pattern,
            isNullable = isNullable,
            isMultiType = multiType,
            typeAlias = patternName,
            collectorContext = collectorContext,
        ).also {
            cacheComponentPattern(patternName, it)
        }
    }

    private fun toSpecmaticParamName(optional: Boolean, name: String) = when (optional) {
        true -> "${name}?"
        false -> name
    }

    private fun resolveReferenceToSchema(component: String, collectorContext: CollectorContext): Pair<String, Schema<*>> {
        val componentName = extractComponentName(component, collectorContext)
        val components = parsedOpenApi.components ?: Components()
        val schemas = components.schemas.orEmpty()
        return componentName to collectorContext.at("\$ref").requirePojo(
            message = {
                "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"$componentName\"."
            },
            extract = { schemas[componentName] },
            createDefault = { Schema<Any>().also { it.properties = emptyMap() } },
            ruleViolation = { OpenApiLintViolations.UNRESOLVED_REFERENCE }
        )
    }

    private fun resolveReferenceToRequestBody(component: String, collectorContext: CollectorContext): Pair<RequestBody, CollectorContext> {
        val componentName = extractComponentName(component, collectorContext)
        val hasRefedOutBody = parsedOpenApi.components?.requestBodies?.contains(componentName) == true
        return Pair(
            first = collectorContext.at("\$ref").requirePojo(
                message = {
                    "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"$componentName\"."
                },
                ruleViolation = { OpenApiLintViolations.UNRESOLVED_REFERENCE },
                extract = { parsedOpenApi.components?.requestBodies?.get(componentName) },
                createDefault = { RequestBody() }
            ),
            second = if (hasRefedOutBody) {
                collectorContext.withPath("components").at("requestBodies").at(componentName)
            } else {
                collectorContext
            }
        )
    }

    private fun extractComponentName(component: String, collectorContext: CollectorContext): String {
        val componentName = componentNameFromReference(component)
        if (!component.startsWith("#")) {
            val refContext = collectorContext.at("\$ref")
            val componentPath = component.substringAfterLast("#")
            val filePath = component.substringBeforeLast("#")
            refContext.record(
                message = "The element referred to at this path was not found in the spec. Please add the missing header/schema/etc named \"$componentName\".",
                ruleViolation = OpenApiLintViolations.UNRESOLVED_REFERENCE
            )
        }

        return componentName
    }

    private fun componentNameFromReference(component: String) = component.substringAfterLast("/")

    private fun toSpecmaticQueryParam(operation: Operation, collectorContext: CollectorContext): HttpQueryParamPattern {
        val parameters = operation.parameters ?: return HttpQueryParamPattern(emptyMap())
        val queryParameters = parameters.safeFilter<QueryParameter>(collectorContext)

        val parametersContext = collectorContext.at("parameters")
        val queryPattern: Map<String, Pattern> = queryParameters.associate { (index, it) ->
            logger.debug("Processing query parameter ${it.name}")
            val queryParamContext = parametersContext.at(index)
            val (resolvedSchema, paramContext) = resolveSchemaIfRefElseAtSchema(it.schema, collectorContext = queryParamContext)
            val specmaticPattern: Pattern? = if (resolvedSchema.isSchema(ARRAY_TYPE)) {
                val itemsSchema = paramContext.requirePojo(extract = { resolvedSchema.items }, createDefault = { Schema<Any>() }, message = { "No items schema defined for array schema defaulting to empty schema" })
                QueryParameterArrayPattern(listOf(toSpecmaticPattern(schema = itemsSchema, typeStack = emptyList(), collectorContext = paramContext.at("items"))), it.name)
            } else if (!resolvedSchema.isSchema(OBJECT_TYPE)) {
                QueryParameterScalarPattern(toSpecmaticPattern(schema = it.schema, typeStack = emptyList(), collectorContext = queryParamContext.at("schema")))
            } else {
                queryParamContext.at("schema").record(
                    message = "Specmatic does not currently support query parameters serialized as objects. Specmatic will ignore this query parameter. Please reach out to the Specmatic team if you need support for this feature.",
                    ruleViolation = OpenApiLintViolations.UNSUPPORTED_FEATURE,
                    isWarning = true
                )
                null
            }

            val queryParamKey = if (it.required == true) it.name else "${it.name}?"
            queryParamKey to specmaticPattern
        }.filterValues { it != null }.mapValues { it.value!! }

        val additionalProperties = additionalPropertiesInQueryParam(queryParameters, collectorContext)
        return HttpQueryParamPattern(queryPattern, additionalProperties)
    }

    private fun additionalPropertiesInQueryParam(queryParameters: List<IndexedValue<QueryParameter>>, collectorContext: CollectorContext): Pattern? {
        val (index, additionalProperties) = queryParameters.firstOrNull { indexedParam ->
            val (_, parameter) = indexedParam
            parameter.schema.isSchema(OBJECT_TYPE, multi = false) && parameter.schema.extractAdditionalProperties() != null
        }?.let {
            it.index to it.value.schema.extractAdditionalProperties()
        } ?: return null

        val additionalPropContext = collectorContext.at(index).at("additionalProperties")
        return when (additionalProperties) {
            true -> AnythingPattern
            is Schema<*> -> toSpecmaticPattern(additionalProperties, emptyList(), collectorContext = additionalPropContext)
            else -> null
        }
    }

    private fun toSpecmaticPathParam(openApiPath: String, operation: Operation, otherPathPatterns: Collection<HttpPathPattern> = emptyList(), collectorContext: CollectorContext): HttpPathPattern {
        val parameters = operation.parameters ?: emptyList()
        val pathSegments: List<String> = openApiPath.removePrefix("/").removeSuffix("/").let {
            if (it.isBlank()) emptyList()
            else it.split("/")
        }

        val pathParamMap = parameters
            .safeFilter<PathParameter>(collectorContext)
            .associateBy { indexedParam -> indexedParam.value.name }

        val parameterContext = collectorContext.at("parameters")
        val pathPattern = pathSegments.mapIndexed { _, pathSegment ->
            logger.debug("Processing path segment $pathSegment")
            if (!isParameter(pathSegment)) {
                return@mapIndexed URLPathSegmentPattern(ExactValuePattern(StringValue(pathSegment)))
            }

            val paramName = pathSegment.removeSurrounding("{", "}")
            val parameter = parameterContext.at(pathParamMap[paramName]?.index ?: DEFAULT_ARRAY_INDEX).requirePojo(
                message = {
                    "The path parameter named \"$paramName\" was declared, but no path parameter definition for \"$paramName\" was found. Please add a definition for \"$paramName\" to the spec."
                },
                extract = { pathParamMap[paramName]?.value },
                ruleViolation = { OpenApiLintViolations.PATH_PARAMETER_MISSING },
                createDefault = {
                    PathParameter().apply {
                        `in` = "path"
                        name = paramName
                        schema = Schema<Any>()
                        required = true
                    }
                }
            )

            val pathParameterContext = parameterContext.at(pathParamMap[paramName]?.index ?: DEFAULT_ARRAY_INDEX)
            URLPathSegmentPattern(
                key = paramName,
                pattern = toSpecmaticPattern(parameter.schema, typeStack = emptyList(), collectorContext = pathParameterContext.at("schema")),
            )
        }

        val specmaticPath = toSpecmaticFormattedPathString(parameters, openApiPath)
        return HttpPathPattern(pathPattern, specmaticPath, otherPathPatterns)
    }

    private fun isParameter(pathSegment: String) = pathSegment.startsWith("{") && pathSegment.endsWith("}")

    private fun toSpecmaticFormattedPathString(parameters: List<Parameter>, openApiPath: String): String {
        val throwAwayCollectorContext = CollectorContext()
        return parameters.safeFilter<PathParameter>(throwAwayCollectorContext).map { it.value }.foldRight(openApiPath) { it, specmaticPath ->
            val pattern = if (it.schema.enum != null) StringPattern("") else toSpecmaticPattern(it.schema, emptyList(), collectorContext = throwAwayCollectorContext)
            specmaticPath.replace("{${it.name}}", "(${it.name}:${pattern.typeName})")
        }
    }

    private fun openApiOperations(pathItem: PathItem): Map<String, Operation> {
        return linkedMapOf<String, Operation?>(
            "POST" to pathItem.post,
            "GET" to pathItem.get,
            "PATCH" to pathItem.patch,
            "PUT" to pathItem.put,
            "DELETE" to pathItem.delete,
            "HEAD" to pathItem.head
        ).filter { (_, value) -> value != null }.map { (key, value) -> key to value!! }.toMap()
    }

    private fun Schema<*>.toSpecmaticPattern(patternName: String, typeStack: List<String>, collectorContext: CollectorContext): Pattern = collectorContext.safely(fallback = { AnythingPattern }, message = "Failed to convert schema to internal representation, defaulting to any schema") {
        if (this.`$ref` != null) return@safely handleReference(this, typeStack, patternName, collectorContext)
        if (this.allOf != null) return@safely handleAllOf(this, typeStack, patternName, collectorContext)
        if (this.oneOf != null) return@safely handleOneOf(this, typeStack, patternName, collectorContext)
        if (this.anyOf != null) return@safely handleAnyOf(this, typeStack, patternName, collectorContext)

        val thisExtensions = this.extensions.orEmpty()
        if (this.const != null || thisExtensions[X_CONST_EXPLICIT]?.toString().toBoolean()) {
            return@safely exactPattern(toValue(this.const), patternName)
        }

        val example = extractFirstExampleAsString()
        val declaredTypes = types ?: setOfNotNull(type)
        val effectiveTypes = declaredTypes.filter { it != NULL_TYPE }

        if (enum != null) return@safely enumPattern(this, patternName, declaredTypes.toList(), collectorContext, example)
        if (effectiveTypes.size > 1) return@safely handleMultiType(this, collectorContext, typeStack, patternName, declaredTypes.toList(), example)

        return@safely when (effectiveTypes.firstOrNull()) {
            // Primitives
            "string" -> when (this.format) {
                "email" -> EmailPattern(example = example)
                "password" -> StringPattern(example = example)
                "uuid" -> UUIDPattern
                "date" -> DatePattern
                "date-time" -> DateTimePattern
                "binary" -> BinaryPattern()
                "byte" -> Base64StringPattern()
                else -> stringPattern(this, patternName = patternName, collectorContext = collectorContext, example = example)
            }
            "integer" -> numberPattern(this, collectorContext = collectorContext, isDoubleFormat = false, example = example)
            "number" -> numberPattern(this, collectorContext = collectorContext, isDoubleFormat = true, example = example)
            "boolean" -> BooleanPattern(example = example)

            // Structural
            "array" -> if (this.xml?.name != null) {
                toXMLPattern(this, typeStack = typeStack, collectorContext = collectorContext)
            } else {
                val itemsSchema = collectorContext.requirePojo(
                    message = { "No items schema defined for array schema defaulting to empty schema" },
                    extract = { this.items },
                    createDefault = { Schema<Any>() }
                )
                ListPattern(
                    pattern = toSpecmaticPattern(itemsSchema, typeStack, collectorContext = collectorContext.at("items")),
                    example = toListExample(this.extractFirstExampleAsJsonNode()),
                )
            }

            "object" -> if (this.xml?.name != null) {
                toXMLPattern(this, typeStack = typeStack, collectorContext = collectorContext)
            } else {
                toJsonObjectPattern(this, patternName, typeStack, collectorContext)
            }

            else -> unknownSchemaToSpecmaticPattern(this, patternName, typeStack, collectorContext)
        }
    }

    private fun unknownSchemaToSpecmaticPattern(schema: Schema<*>, patternName: String, typeStack: List<String>, collectorContext: CollectorContext): Pattern {
        val hasNoPropertiesOrRef = schema.additionalProperties == null && schema.`$ref` == null
        if (schema.isNullable() && hasNoPropertiesOrRef) return NullPattern

        val hasProperties = !schema.properties.isNullOrEmpty()
        val hasAdditionalProps = schema.additionalProperties != null && schema.additionalProperties != false
        if (hasProperties || hasAdditionalProps) return toJsonObjectPattern(schema, patternName, typeStack, collectorContext)

        val declaredTypes = schema.types ?: setOfNotNull(schema.type)
        return collectorContext
            .check(AnythingPattern, isValid = { declaredTypes.isEmpty() })
            .violation { OpenApiLintViolations.SCHEMA_UNCLEAR }
            .message {
                val declaredType = declaredTypes.joinToString(", ").ifBlank { "unknown-type" }
                "Could not recognize type \"$declaredType\". Please share this error with the Specmatic team."
            }
            .orUse { AnythingPattern }
            .build(isWarning = true)
    }

    private fun Schema<*>.extractFirstExampleAsString(): String? {
        val example = this.examples?.firstNotNullOfOrNull { it } ?: this.example ?: return null
        return example.let(::toValue).let(Value::toUnformattedString)
    }

    private fun Schema<*>.extractAdditionalProperties(): Any? {
        val additionalProperties = this.additionalProperties
        return if (additionalProperties is Schema<*> && additionalProperties.booleanSchemaValue != null) {
            additionalProperties.booleanSchemaValue
        } else {
            additionalProperties
        }
    }

    private fun Schema<*>.extractFirstExampleAsJsonNode(): JsonNode? {
        val example = this.examples?.firstOrNull() ?: this.example
        return jsonMapper.valueToTree(example)
    }

    private fun Schema<*>.isNullable(): Boolean {
        if (this.nullable != null) return this.nullable
        return (this.types ?: setOfNotNull(this.type)).contains(NULL_TYPE)
    }

    private fun Schema<*>.isBinarySchema(): Boolean {
        val types = (this.types ?: setOfNotNull(this.type))
        return types.contains("string") && this.format == BINARY_FORMAT
    }

    private fun Schema<*>.isPrimitive(): Boolean {
        val meta = schemaMeta()
        return meta.effectiveTypes.isNotEmpty() && meta.effectiveTypes.all {
            it == "string" || it == "number" || it == "integer" || it == "boolean"
        }
    }

    private fun Schema<*>.isSchema(type: String, nullable: Boolean? = null, multi: Boolean? = null): Boolean {
        val meta = schemaMeta()
        if (nullable != null && nullable != meta.isNullable) return false
        if (multi != null && multi != meta.isMulti) return false
        return type in meta.effectiveTypes
    }

    private data class SchemaMeta(val isNullable: Boolean, val isMulti: Boolean, val effectiveTypes: List<String>)
    private fun Schema<*>.schemaMeta(): SchemaMeta {
        val declared = types ?: setOfNotNull(this.type)
        val isNullable = this.nullable == true || NULL_TYPE in declared
        val effectiveTypes = declared.filterNot { it == NULL_TYPE }
        val isMulti = effectiveTypes.size > 1
        return SchemaMeta(isNullable, isMulti, effectiveTypes)
    }

    private inline fun <reified T: Parameter> List<Parameter>.safeFilter(collectorContext: CollectorContext): List<IndexedValue<T>> {
        val parameterContext = collectorContext.at("parameters")
        return this.mapIndexedNotNull { index, parameter ->
            if (parameter !is T) return@mapIndexedNotNull null
            val itemContext = parameterContext.at(index)
            val validNameParam = itemContext.check<T?>(parameter) { it?.name != null }
                .violation { OpenApiLintViolations.INVALID_PARAMETER_DEFINITION }
                .message {
                    "\"name\" is mandatory for parameters, but it was missing. It will be ignored by Specmatic. Please add a name, or remove the parameter."
                }
                .orUse { null }
                .build() ?: return@mapIndexedNotNull null

            val schemaEnsuredParameter = itemContext.check(value = validNameParam) { it.schema != null }
                .message { "Parameter has no schema defined, defaulting to empty schema" }
                .orUse { validNameParam.apply { schema = Schema<Any>() } }
                .build()

            val itemsSchemaEnsuredParameter = itemContext.at("schema").check(value = schemaEnsuredParameter) { !it.schema.isSchema("array") || it.schema.items != null }
                .message { "Array Parameter has no items schema defined, defaulting to empty schema" }
                .orUse { schemaEnsuredParameter.apply { schema.apply { items = Schema<Any>() } } }
                .build()

            IndexedValue(index, itemsSchemaEnsuredParameter)
        }
    }

    private fun <T> CollectorContext.requirePojo(extract: () -> T?, createDefault: () -> T, message: () -> String, ruleViolation: () -> RuleViolation? = { null }, isWarning: Boolean = false): T {
        val parameter = this.safely(fallback = { null }) { extract() }
        return this.checkOptional(value = parameter, isValid = { parameter != null })
            .violation(ruleViolation)
            .message(message)
            .orUse { createDefault() }
            .build(isWarning)
    }

    private fun <T> CollectorContext.checkPojo(value: T, isValid: (T) -> Boolean, createDefault: (T) -> T, message: () -> String, ruleViolation: () -> RuleViolation? = { null }, isWarning: Boolean = false): T {
        return this.check(value = value, isValid = { isValid(it) })
            .violation(ruleViolation)
            .message(message)
            .orUse { createDefault(value) }
            .build(isWarning)
    }
}

private fun <T> Result.returnLenientlyElseFail(lenient: Boolean = false, value: T): T {
    if ((this is Failure && this.isPartial) || lenient) {
        logger.log(this.reportString())
        return value
    }

    this.throwOnFailure()
    return value
}

internal fun validateSecuritySchemeParameterDuplication(securitySchemes: List<OpenAPISecurityScheme>, parameters: List<IndexedValue<Parameter>>?, collectorContext: CollectorContext) {
    securitySchemes.forEach { securityScheme ->
        securityScheme.collectErrorIfExistsInParameters(parameters.orEmpty(), collectorContext)
    }
}
