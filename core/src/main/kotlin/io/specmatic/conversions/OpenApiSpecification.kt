package io.specmatic.conversions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import io.cucumber.messages.types.Step
import io.ktor.util.reflect.*
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
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.PathParameter
import io.swagger.v3.oas.models.parameters.QueryParameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.core.models.SwaggerParseResult
import java.io.File

private const val BEARER_SECURITY_SCHEME = "bearer"
const val SERVICE_TYPE_HTTP = "HTTP"

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
    val tags: List<String> = emptyList<String>(),
    val summary: String = "",
    val description: String = "",
    val operationId: String = ""
)

class OpenApiSpecification(
    private val openApiFilePath: String,
    private val parsedOpenApi: OpenAPI,
    private val sourceProvider: String? = null,
    private val sourceRepository: String? = null,
    private val sourceRepositoryBranch: String? = null,
    private val specificationPath: String? = null,
    private val securityConfiguration: SecurityConfiguration? = null,
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
    private val dictionary: Dictionary = loadDictionary(openApiFilePath, specmaticConfig.getStubDictionary()),
    private val strictMode: Boolean = false
) : IncludedSpecification, ApiSpecification {
    init {
        logger.log(openApiSpecificationInfo(openApiFilePath, parsedOpenApi))
    }

    companion object {

        fun fromFile(openApiFilePath: String, relativeTo: String = ""): OpenApiSpecification {
            val openApiFile = File(openApiFilePath).let { openApiFile ->
                if (openApiFile.isAbsolute) {
                    openApiFile
                } else {
                    File(relativeTo).canonicalFile.parentFile.resolve(openApiFile)
                }
            }

            return fromFile(openApiFile.canonicalPath)
        }

        fun fromFile(openApiFilePath: String): OpenApiSpecification {
            return fromFile(openApiFilePath, SpecmaticConfig())
        }

        fun fromFile(openApiFilePath: String, specmaticConfig: SpecmaticConfig): OpenApiSpecification {
            return OpenApiSpecification(openApiFilePath, getParsedOpenApi(openApiFilePath), specmaticConfig = specmaticConfig)
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

        fun checkSpecValidity(openApiFilePath: String) {
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
            if (parseResult.messages?.isNotEmpty() == true) {
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
            loggerForErrors: LogStrategy = logger,
            sourceProvider: String? = null,
            sourceRepository: String? = null,
            sourceRepositoryBranch: String? = null,
            specificationPath: String? = null,
            securityConfiguration: SecurityConfiguration? = null,
            specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
            overlayContent: String = "",
            strictMode: Boolean = false
        ): OpenApiSpecification {
            val implicitOverlayFile = getImplicitOverlayContent(openApiFilePath)

            val parseResult: SwaggerParseResult =
                OpenAPIV3Parser().readContents(
                    yamlContent.applyOverlay(overlayContent).applyOverlay(implicitOverlayFile),
                    null,
                    resolveExternalReferences(),
                    openApiFilePath
                )
            val parsedOpenApi: OpenAPI? = parseResult.openAPI

            if (parsedOpenApi == null) {
                logger.debug("Failed to parse OpenAPI from file $openApiFilePath\n\n$yamlContent")

                printMessages(parseResult, openApiFilePath, loggerForErrors)

                throw ContractException("Could not parse contract $openApiFilePath, please validate the syntax using https://editor.swagger.io")
            } else if (parseResult.messages?.isNotEmpty() == true) {
                logger.log("The OpenAPI file $openApiFilePath was read successfully but with some issues")

                printMessages(parseResult, openApiFilePath, loggerForErrors)
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
                strictMode = strictMode
            )
        }

        fun loadDictionary(openApiFilePath: String, dictionaryPathFromConfig: String?): Dictionary {
            val dictionaryFile = getDictionaryFile(File(openApiFilePath), dictionaryPathFromConfig)
            return if (dictionaryFile != null) Dictionary.from(dictionaryFile) else Dictionary.empty()
        }

        private fun getDictionaryFile(openApiFile: File, dictionaryPathFromConfig: String?): File? {
            val explicitDictionaryPath = dictionaryPathFromConfig ?: Flags.getStringValue(SPECMATIC_STUB_DICTIONARY)
            if (!explicitDictionaryPath.isNullOrEmpty()) return File(explicitDictionaryPath)

            val implicitPaths = sequenceOf("_dictionary.yml", "_dictionary.yaml", "_dictionary.json")
            return implicitPaths.map {
                openApiFile.canonicalFile.parentFile.resolve(openApiFile.nameWithoutExtension + it)
            }.firstOrNull(File::exists)
        }

        private fun printMessages(parseResult: SwaggerParseResult, filePath: String, loggerForErrors: LogStrategy) {
            parseResult.messages.filterNotNull().let {
                if (it.isNotEmpty()) {
                    val parserMessages = parseResult.messages.joinToString(System.lineSeparator())
                    loggerForErrors.log("Error parsing file $filePath")
                    loggerForErrors.log(parserMessages.prependIndent("  "))
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

        fun String.applyOverlay(overlayContent: String): String {
            if(overlayContent.isBlank())
                return this

            return OverlayMerger().merge(this, OverlayParser.parse(overlayContent))
        }
    }

    val patterns = mutableMapOf<String, Pattern>()

    private val pathTree: PathTree = PathTree.from(parsedOpenApi.paths.orEmpty())

    fun isOpenAPI31(): Boolean {
        return parsedOpenApi.openapi.startsWith("3.1")
    }

    fun toFeature(): Feature {
        val name = File(openApiFilePath).name

        val (scenarioInfos, stubsFromExamples) = toScenarioInfos()
        val unreferencedSchemaPatterns = parseUnreferencedSchemas()
        val updatedScenarios = scenarioInfos.map {
            Scenario(it).copy(
                dictionary = dictionary.plus(specmaticConfig.parsedDefaultPatternValues()),
                attributeSelectionPattern = specmaticConfig.getAttributeSelectionPattern(),
                patterns = it.patterns + unreferencedSchemaPatterns
            )
        }

        return Feature.from(
            updatedScenarios, name = name, path = openApiFilePath, sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            specification = specificationPath,
            serviceType = SERVICE_TYPE_HTTP,
            stubsFromExamples = stubsFromExamples,
            specmaticConfig = specmaticConfig,
            strictMode = strictMode
        )
    }

    private fun parseUnreferencedSchemas(): Map<String, Pattern> {
        return openApiSchemas().filterNot { withPatternDelimiters(it.key) in patterns }.map {
            withPatternDelimiters(it.key) to toSpecmaticPattern(it.value, emptyList(), it.key)
        }.toMap()
    }

    override fun toScenarioInfos(): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val (
            scenarioInfos: List<ScenarioInfo>,
            examplesAsExpectations: Map<String, List<Pair<HttpRequest, HttpResponse>>>
        ) = openApiToScenarioInfos()

        return scenarioInfos.filter { it.httpResponsePattern.status > 0 } to examplesAsExpectations
    }

    override fun matches(
        specmaticScenarioInfo: ScenarioInfo, steps: List<Step>
    ): List<ScenarioInfo> {
        val (openApiScenarioInfos, _) = openApiToScenarioInfos()
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

    private fun openApiToScenarioInfos(): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val data: List<Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>>> =
            openApiPaths().map { (openApiPath, pathItem) ->
                val scenariosAndExamples = openApiOperations(pathItem).map { (httpMethod, openApiOperation) ->
                    logger.debug("${System.lineSeparator()}Processing $httpMethod $openApiPath")

                    try {
                        openApiOperation.validateParameters()
                    } catch (e: ContractException) {
                        throw ContractException("In $httpMethod $openApiPath: ${e.message}")
                    }

                    val operation = openApiOperation.operation

                    val specmaticPathParam = toSpecmaticPathParam(openApiPath, operation)
                    val specmaticQueryParam = toSpecmaticQueryParam(operation)

                    val httpResponsePatterns: List<ResponsePatternData> =
                        attempt(breadCrumb = "$httpMethod $openApiPath -> RESPONSE") {
                            toHttpResponsePatterns(operation.responses, httpMethod, openApiPath, parsedOpenApi.components?.schemas.orEmpty())
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
                                specmaticPathParam, specmaticQueryParam, httpMethod, operation, parsedOpenApi.components?.schemas.orEmpty()
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
                            testRowsFromExamples(responseExamples, requestExamples, operation, openApiRequest, first2xxResponseStatus)
                        val scenarioName = scenarioName(operation, response, httpRequestPattern)

                        val ignoreFailure = operation.tags.orEmpty().map { it.trim() }.contains("WIP")

                        val rowsToBeUsed: List<Row> = specmaticExampleRows

                        val operationMetadata = OperationMetadata(
                            tags = operation.tags.orEmpty(),
                            summary = operation.summary.orEmpty(),
                            description = operation.description.orEmpty(),
                            operationId = operation.operationId.orEmpty()
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
                            serviceType = SERVICE_TYPE_HTTP,
                            operationMetadata = operationMetadata
                        )
                    }

                    val responseExamplesList = httpResponsePatterns.map { it.examples }

                    val requestExamples = httpRequestPatterns.map {
                        it.examples
                    }.foldRight(emptyMap<String, List<HttpRequest>>()) { acc, map ->
                        acc.plus(map)
                    }

                    val examples =
                        collateExamplesForExpectations(requestExamples, responseExamplesList, httpRequestPatterns)

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
                                        operation,
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

        logger.newLine()
        return scenarioInfos to examples
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
                    parameterExamples(operation, key) as Map<String, String>
                } catch (e: Exception) {
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

    private fun getRequestExamplesForRequestWithNoParamsAndBody(
        operation: Operation,
        requestExamples: Map<String, List<HttpRequest>>,
        responseExamplesList: List<Map<String, HttpResponse>>,
        httpRequestPatterns: List<RequestPatternsData>
    ): Map<String, List<HttpRequest>> {
        if(operation.requestBody != null || operation.parameters != null || requestExamples.isNotEmpty()) {
            return emptyMap()
        }

        return responseExamplesList.flatMap { responseExamples ->
            responseExamples.map {
                it.key to httpRequestPatterns.map { it.requestPattern.generate(Resolver()) }
            }
        }.toMap()
    }

    private fun validateParameters(parameters: List<Parameter>?) {
        parameters.orEmpty().forEach { parameter ->
            if(parameter.name == null)
                throw ContractException("A parameter does not have a name.")

            if(parameter.schema == null)
                throw ContractException("A parameter does not have a schema.")

            if(parameter.schema.type == "array" && parameter.schema.items == null)
                throw ContractException("A parameter of type \"array\" has not defined \"items\".")

        }
    }

    private fun collateExamplesForExpectations(
        requestExamples: Map<String, List<HttpRequest>>,
        responseExamplesList: List<Map<String, HttpResponse>>,
        httpRequestPatterns: List<RequestPatternsData>
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

    private fun requestBodyExampleNames(
        openApiRequest: Pair<String, MediaType>?,
    ): Set<String> {
        if(openApiRequest == null)
            return emptySet()

        val (_, requestBodyMediaType) = openApiRequest

        val requestExampleValue =
            requestBodyMediaType.examples.orEmpty().keys

        return requestExampleValue
    }

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
        return example?.`$ref`?.let {
            val exampleName = it.substringAfterLast("/")
            parsedOpenApi.components?.examples?.get(exampleName)
        } ?: example
    }

    private fun parameterExamples(
        operation: Operation,
        exampleName: String
    ): Map<String, Any> = operation.parameters.orEmpty()
        .filter { parameter ->
            parameter.examples.orEmpty().any { it.key == exampleName }
        }.associate {
            val exampleValue: Example = it.examples[exampleName]
                ?: throw ContractException("The value of ${it.name} in example $exampleName was unexpectedly found to be null.")

            it.name to (resolveExample(exampleValue)?.value ?: "")
        }

    private fun openApiPaths() = parsedOpenApi.paths.orEmpty()

    private fun openApiSchemas() = parsedOpenApi.components?.schemas.orEmpty()

    private fun isNumber(value: String): Boolean {
        return value.toIntOrNull() != null
    }

    private fun toHttpResponsePatterns(
        responses: ApiResponses?,
        method: String,
        path: String,
        schemas: Map<String, Schema<Any>>
    ): List<ResponsePatternData> {
        return responses.orEmpty().map { (status, response) ->
            logger.debug("Processing response payload with status $status")

            val headersMap = openAPIHeadersToSpecmatic(response)
            if(!isNumber(status) && status != "default")
                throw ContractException("Response status codes are expected to be numbers, but \"$status\" was found")

            attempt(breadCrumb = status) {
                openAPIResponseToSpecmatic(response, status, headersMap, method, path, schemas)
            }
        }.flatten()
    }

    private fun openAPIHeadersToSpecmatic(response: ApiResponse) =
        response.headers.orEmpty().map { (headerName, header) ->
            logger.debug("Processing response header $headerName")

            toSpecmaticParamName(header.required != true, headerName) to toSpecmaticPattern(
                resolveResponseHeader(header)?.schema ?: throw ContractException(
                    headerComponentMissingError(
                        headerName,
                        response
                    )
                ), emptyList()
            )
        }.toMap()

    data class ResponsePatternData(
        val response: ApiResponse,
        val mediaType: MediaType,
        val responsePattern: HttpResponsePattern,
        val examples: Map<String, HttpResponse>
    )

    private fun headerComponentMissingError(headerName: String, response: ApiResponse): String {
        if (response.description != null) {
            return "Header component not found for header $headerName in response \"${response.description}\""
        }

        return "Header component not found for header $headerName"
    }

    private fun resolveResponseHeader(header: Header): Header? {
        return if (header.`$ref` != null) {
            val headerComponentName = header.`$ref`.substringAfterLast("/")
            parsedOpenApi.components?.headers?.get(headerComponentName)
        } else {
            header
        }
    }

    private fun openAPIResponseToSpecmatic(
        response: ApiResponse,
        status: String,
        headersMap: Map<String, Pattern>,
        method: String,
        path: String,
        schemas: Map<String, Schema<Any>>
    ): List<ResponsePatternData> {
        if (response.content == null || response.content.isEmpty()) {
            val responsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(headersMap),
                body = NoBodyPattern,
                status = status.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
            )

            return listOf(ResponsePatternData(response, MediaType(), responsePattern, emptyMap()))
        }

        val headerExamples =
            if (specmaticConfig.getIgnoreInlineExamples() || getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES))
                emptyMap()
            else
                response.headers.orEmpty().entries.fold(emptyMap<String, Map<String, String>>()) { acc, (headerName, header) ->
                    extractParameterExamples(header.examples, headerName, acc)
                }

        val contentTypeHeaderPattern = headersMap.entries.find { it.key.lowercase() in listOf("content-type", "content-type?") }?.value

        return response.content.map { (contentType, mediaType) ->
            logger.debug("Processing response with content type $contentType")

            val actualContentType = if(contentTypeHeaderPattern != null) {
                val descriptor = "response of $method $path"
                getAndLogActualContentTypeHeader(contentTypeHeaderPattern, contentType, descriptor, schemas) ?: contentType
            } else contentType

            val responsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(headersMap, contentType = contentType),
                status = if (status == "default") 1000 else status.toInt(),
                body = when (contentType) {
                    "application/xml" -> toXMLPattern(mediaType)
                    else -> toSpecmaticPattern(mediaType, "response")
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
                            if(headerExample.entries.find { it.key.lowercase() == "content-type" } == null)
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

    private fun parseOperationSecuritySchemas(
        operation: Operation,
        method: String,
        path: String,
        securitySchemeComponents: Map<String, OpenAPISecurityScheme>
    ): List<OpenAPISecurityScheme> {
        logger.debug("Associating security schemes")
        val securitySchemes = operation.security ?: parsedOpenApi.security
        if (securitySchemes.isNullOrEmpty()) return listOf(NoSecurityScheme())

        fun getSecurityScheme(name: String): OpenAPISecurityScheme {
            val scheme = securitySchemeComponents[name]
                ?: throw ContractException("Security scheme $name not found in $method $path")
            return scheme
        }

        return securitySchemes.map {
            when (it.keys.size) {
                0 -> NoSecurityScheme()
                1 -> getSecurityScheme(it.keys.single())
                else -> CompositeSecurityScheme(it.keys.map(::getSecurityScheme))
            }
        }
    }

    private fun toHttpRequestPatterns(
        httpPathPattern: HttpPathPattern,
        httpQueryParamPattern: HttpQueryParamPattern,
        httpMethod: String,
        operation: Operation,
        schemas: Map<String, Schema<Any>>
    ): List<RequestPatternsData> {
        logger.debug("Processing requests for $httpMethod")

        val securitySchemeEntries = parsedOpenApi.components?.securitySchemes.orEmpty()
        val securitySchemeComponents = securitySchemeEntries.entries.associate { (schemeName, scheme) ->
            schemeName to toSecurityScheme(schemeName, scheme)
        }

        val securitySchemesForRequestPattern = parseOperationSecuritySchemas(operation, httpMethod, httpPathPattern.path, securitySchemeComponents)

        val parameters = operation.parameters

        val headersMap = parameters.orEmpty().filterIsInstance<HeaderParameter>().associate {
            logger.debug("Processing request header ${it.name}")

            toSpecmaticParamName(it.required != true, it.name) to toSpecmaticPattern(it.schema, emptyList())
        }

        val contentTypeHeaderPattern = headersMap.entries.find { it.key.lowercase() in listOf("content-type", "content-type?") }?.value

        val headersPattern = HttpHeadersPattern(headersMap)
        val requestPattern = HttpRequestPattern(
            httpPathPattern = httpPathPattern,
            httpQueryParamPattern = httpQueryParamPattern,
            method = httpMethod,
            headersPattern = headersPattern,
            securitySchemes = securitySchemesForRequestPattern
        )

        val exampleQueryParams = namedExampleParams(operation, QueryParameter::class.java)
        val examplePathParams = namedExampleParams(operation, PathParameter::class.java)
        val exampleHeaderParams = namedExampleParams(operation, HeaderParameter::class.java)

        val exampleRequestBuilder = ExampleRequestBuilder(
            examplePathParams,
            exampleHeaderParams,
            exampleQueryParams,
            httpPathPattern,
            httpMethod,
            securitySchemesForRequestPattern
        )

        val requestBody = resolveRequestBody(operation)
            ?: return listOf(
                RequestPatternsData(
                    requestPattern.copy(body = NoBodyPattern),
                    exampleRequestBuilder.examplesBasedOnParameters
                )
            )

        return requestBody.content.map { (contentType, mediaType) ->
            logger.debug("Processing request payload with media type $contentType")

            when (contentType.lowercase()) {
                "multipart/form-data" -> {
                    val partSchemas = if (mediaType.schema.`$ref` == null) {
                        mediaType.schema
                    } else {
                        resolveReferenceToSchema(mediaType.schema.`$ref`).second
                    }

                    val parts: List<MultiPartFormDataPattern> =
                        partSchemas.properties.map { (partName, partSchema) ->
                            val partContentType = mediaType.encoding?.get(partName)?.contentType
                            val partNameWithPresence = if (partSchemas.required?.contains(partName) == true)
                                partName
                            else
                                "$partName?"

                            if (partSchema is BinarySchema) {
                                MultiPartFilePattern(
                                    partNameWithPresence,
                                    toSpecmaticPattern(partSchema, emptyList()),
                                    partContentType
                                )
                            } else {
                                MultiPartContentPattern(
                                    partNameWithPresence,
                                    toSpecmaticPattern(partSchema, emptyList()),
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
                        formFieldsPattern = toFormFields(mediaType),
                        headersPattern = headersPatternWithContentType(requestPattern, contentType)
                    ), emptyMap()
                )

                "application/xml" -> Pair(
                    requestPattern.copy(
                        body = toXMLPattern(mediaType),
                        headersPattern = headersPatternWithContentType(requestPattern, contentType)
                    ), emptyMap()
                )

                else -> {
                    val actualContentType = if(contentTypeHeaderPattern != null) {
                        val descriptor = "request of $httpMethod ${httpPathPattern.path}"
                        getAndLogActualContentTypeHeader(contentTypeHeaderPattern, contentType, descriptor, schemas) ?: contentType
                    } else contentType

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

                    val body = toSpecmaticPattern(mediaType, "request").let {
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

    private fun getAndLogActualContentTypeHeader(
        contentTypeHeaderPattern: Pattern,
        contentType: String?,
        descriptor: String,
        schemas: Map<String, Schema<Any>>,
    ): String? {
        val concretePattern = when (contentTypeHeaderPattern) {
            is DeferredPattern -> {
                val schemaPath = withoutPatternDelimiters(contentTypeHeaderPattern.pattern)
                val componentName = schemaPath.split("/").lastOrNull() ?: return contentType
                val schema = schemas[componentName] ?: return contentType
                val pattern = toSpecmaticPattern(schema, listOf(componentName), componentName, false)
                return getAndLogActualContentTypeHeader(pattern, contentType, descriptor, schemas)
            }
            else -> contentTypeHeaderPattern
        }

        try {
            val generated1 = concretePattern.generate(Resolver()).toStringLiteral()
            val generated2 = concretePattern.generate(Resolver()).toStringLiteral()

            if (generated1 == generated2 && generated1 != contentType) {
                val warning = "WARNING: Media type \"$contentType\" in $descriptor does not match the respective Content-Type header. Using the Content-Type header as an override."
                logger.log(warning)
                return generated1
            }
        } catch (e: ContractException) {
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

    private fun <T : Parameter> namedExampleParams(
        operation: Operation,
        parameterType: Class<T>
    ): Map<String, Map<String, String>> {
        if (specmaticConfig.getIgnoreInlineExamples() || getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES))
            return emptyMap()

        return operation.parameters.orEmpty()
            .filterIsInstance(parameterType)
            .fold(emptyMap()) { acc, parameter ->
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

    private fun resolveRequestBody(operation: Operation): RequestBody? =
        operation.requestBody?.`$ref`?.let {
            resolveReferenceToRequestBody(it).second
        } ?: operation.requestBody

    private fun toSecurityScheme(schemeName: String, securityScheme: SecurityScheme): OpenAPISecurityScheme {
        val securitySchemeConfiguration =
            securityConfiguration?.getOpenAPISecurityScheme(schemeName)
        if (securityScheme.scheme == BEARER_SECURITY_SCHEME) {
            return toBearerSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.OAUTH2) {
            return toBearerSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.APIKEY) {
            val apiKey = getSecurityTokenForApiKeyScheme(securitySchemeConfiguration, schemeName)
            if (securityScheme.`in` == SecurityScheme.In.HEADER)
                return APIKeyInHeaderSecurityScheme(securityScheme.name, apiKey)

            if (securityScheme.`in` == SecurityScheme.In.QUERY)
                return APIKeyInQueryParamSecurityScheme(securityScheme.name, apiKey)
        }

        if(securityScheme.type == SecurityScheme.Type.HTTP && securityScheme.scheme == "basic")
            return toBasicAuthSecurityScheme(securitySchemeConfiguration, schemeName)

        throw ContractException("Specmatic only supports oauth2, bearer, and api key authentication (header, query) security schemes at the moment")
    }

    private fun toBearerSecurityScheme(
        securitySchemeConfiguration: SecuritySchemeConfiguration?,
        environmentVariable: String,
    ): BearerSecurityScheme {
        val token = getSecurityTokenForBearerScheme(securitySchemeConfiguration, environmentVariable)
        return BearerSecurityScheme(token)
    }

    private fun toBasicAuthSecurityScheme(
        securitySchemeConfiguration: SecuritySchemeConfiguration?,
        environmentVariable: String,
    ): BasicAuthSecurityScheme {
        val token = getSecurityTokenForBasicAuthScheme(securitySchemeConfiguration, environmentVariable)
        return BasicAuthSecurityScheme(token)
    }

    private fun toFormFields(mediaType: MediaType): Map<String, Pattern> {
        val schema = mediaType.schema.`$ref`?.let {
            val (_, resolvedSchema) = resolveReferenceToSchema(mediaType.schema.`$ref`)
            resolvedSchema
        } ?: mediaType.schema

        return schema.properties.map { (formFieldName, formFieldValue) ->
            formFieldName to toSpecmaticPattern(
                formFieldValue, emptyList(), jsonInFormData = isJsonInString(mediaType, formFieldName)
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

    private fun toSpecmaticPattern(mediaType: MediaType, section: String, jsonInFormData: Boolean = false): Pattern =
        toSpecmaticPattern(mediaType.schema ?: throw ContractException("${section.capitalizeFirstChar()} body definition is missing"), emptyList(), jsonInFormData = jsonInFormData)

    private fun resolveDeepAllOfs(schema: Schema<Any>, discriminatorDetails: DiscriminatorDetails, typeStack: Set<String>, topLevel: Boolean): Pair<List<Schema<Any>>, DiscriminatorDetails> {
        if (schema.allOf == null)
            return listOf(schema) to discriminatorDetails

        // Pair<String [property name], Map<String [possible value], Pair<String [Schema name derived from the ref], Schema<Any> [reffed schema]>>>
        val newDiscriminatorDetailsDetails: Triple<String, Map<String, Pair<String, List<Schema<Any>>>>, DiscriminatorDetails>? = if (!topLevel) null else schema.discriminator?.let { rawDiscriminator ->
            rawDiscriminator.propertyName?.let { propertyName ->
                val mapping = rawDiscriminator.mapping ?: emptyMap()

                val mappingWithSchemaListAndDiscriminator =
                    mapping.entries.mapNotNull { (discriminatorValue, refPath) ->
                        val (mappedSchemaName, mappedSchema) = resolveReferenceToSchema(refPath)
                        val mappedComponentName = extractComponentName(refPath)
                        if (mappedComponentName !in typeStack) {
                            val value = mappedSchemaName to resolveDeepAllOfs(
                                mappedSchema,
                                discriminatorDetails,
                                typeStack + mappedComponentName,
                                topLevel = false
                            )
                            discriminatorValue to value
                        } else {
                            null
                        }
                    }.toMap()

                val discriminatorsFromResolvedMappingSchemas = mappingWithSchemaListAndDiscriminator.values.map { (possiblePropertyValue, discriminator) ->
                    discriminator.second
                }

                val mergedDiscriminatorDetailsFromMappingSchemas = discriminatorsFromResolvedMappingSchemas.fold(DiscriminatorDetails()) { acc, discriminator ->
                    acc.plus(discriminator)
                }

                val mappingWithSchema: Map<String, Pair<String, List<Schema<Any>>>> = mappingWithSchemaListAndDiscriminator.mapValues { entry: Map.Entry<String, Pair<String, Pair<List<Schema<Any>>, DiscriminatorDetails>>> ->
                    entry.value.first to (entry.value.second.first)
                }

                Triple(propertyName, mappingWithSchema, mergedDiscriminatorDetailsFromMappingSchemas)
            }
        }

        val allOfs = schema.allOf.mapNotNull { constituentSchema ->
            if (constituentSchema.`$ref` != null) {
                val (_, referredSchema) = resolveReferenceToSchema(constituentSchema.`$ref`)

                val componentName = extractComponentName(constituentSchema.`$ref`)

                if (componentName !in typeStack) {
                    resolveDeepAllOfs(
                        referredSchema,
                        discriminatorDetails.plus(newDiscriminatorDetailsDetails),
                        typeStack + componentName,
                        topLevel = false
                    )
                } else
                    null
            } else listOf(constituentSchema) to discriminatorDetails
        }

        val discriminatorDetailsForThisLevel = newDiscriminatorDetailsDetails?.let { DiscriminatorDetails(mapOf(newDiscriminatorDetailsDetails.first to newDiscriminatorDetailsDetails.second)) } ?: DiscriminatorDetails()

        return allOfs.fold(Pair(emptyList(), discriminatorDetailsForThisLevel)) { acc, item ->
            val (accSchemas, accDiscriminator) = acc
            val (additionalSchemas, additionalSchemasDiscriminator) = item

            accSchemas.plus(additionalSchemas) to accDiscriminator.plus(additionalSchemasDiscriminator)
        }
    }

    private fun toSpecmaticPattern(
        schema: Schema<*>, typeStack: List<String>, patternName: String = "", jsonInFormData: Boolean = false
    ): Pattern {
        if(patternName.isNotBlank()) logger.debug("Processing schema $patternName")

        val preExistingResult = patterns["($patternName)"]
        val pattern = if (preExistingResult != null && patternName.isNotBlank())
            preExistingResult
        else if (typeStack.filter { it == patternName }.size > 1) {
            DeferredPattern("($patternName)")
        } else if (schema.`$ref` != null) {
            val component: String = schema.`$ref`
            val (componentName, referredSchema) = resolveReferenceToSchema(component)
            val cyclicReference = typeStack.contains(componentName)
            if (!cyclicReference) {
                val componentPattern = toSpecmaticPattern(
                    referredSchema,
                    typeStack.plus(componentName), componentName
                )
                cacheComponentPattern(componentName, componentPattern)
            }
            DeferredPattern("(${componentName})")
        } else when (schema) {
            is StringSchema -> when (schema.enum) {
                null -> StringPattern(
                    minLength = schema.minLength,
                    maxLength = schema.maxLength,
                    example = schema.example?.toString(),
                    regex = schema.pattern
                )

                else -> toEnum(schema, patternName) { enumValue -> StringValue(enumValue.toString()) }.withExample(
                    schema.example?.toString()
                )
            }

            is EmailSchema -> EmailPattern(example = schema.example?.toString())

            is PasswordSchema -> StringPattern(example = schema.example?.toString())

            is IntegerSchema -> when (schema.enum) {
                null -> numberPattern(schema, false)
                else -> toEnum(schema, patternName) { enumValue ->
                    NumberValue(
                        enumValue.toString().toInt()
                    )
                }.withExample(schema.example?.toString())
            }

            is BinarySchema -> BinaryPattern()
            is NumberSchema -> numberPattern(schema, true)
            is UUIDSchema -> UUIDPattern
            is DateTimeSchema -> DateTimePattern
            is DateSchema -> DatePattern
            is BooleanSchema -> BooleanPattern(example = schema.example?.toString())
            is ObjectSchema, is MapSchema -> {
                if (schema.xml?.name != null) {
                    toXMLPattern(schema, typeStack = typeStack)
                } else {
                    toJsonObjectPattern(schema, patternName, typeStack)
                }
            }
            is ByteArraySchema -> Base64StringPattern()

            is ArraySchema -> {
                if (schema.xml?.name != null) {
                    toXMLPattern(schema, typeStack = typeStack)
                } else {

                    ListPattern(
                        toSpecmaticPattern(
                            schema.items, typeStack
                        ),
                        example = toListExample(schema.example)
                    )
                }
            }

            is ComposedSchema -> {
                if (schema.allOf != null) {
                    val (deepListOfAllOfs, allDiscriminators) = resolveDeepAllOfs(schema, DiscriminatorDetails(), emptySet(), topLevel = true)

                    val explodedDiscriminators = allDiscriminators.explode()
                    val topLevelRequired = schema.required.orEmpty()

                    val schemaProperties = explodedDiscriminators.map { discriminator ->
                        val schemasFromDiscriminator = discriminator.schemas

                        val schemaProperties = (deepListOfAllOfs + schemasFromDiscriminator).map { schemaToProcess ->
                            val requiredFields = topLevelRequired.plus(schemaToProcess.required.orEmpty())
                            SchemaProperty(
                                extensions = schemaToProcess.extensions.orEmpty(),
                                properties = toSchemaProperties(
                                    schemaToProcess,
                                    requiredFields.distinct(),
                                    patternName,
                                    typeStack,
                                    discriminator
                                )
                            )
                        }.fold(SchemaProperty(extensions = emptyMap(), properties = emptyMap())) { propertiesAcc, propertiesEntry ->
                            val (extensions, properties) = propertiesEntry
                            propertiesAcc.copy(
                                extensions = propertiesAcc.extensions.plus(extensions),
                                properties = combine(properties, propertiesAcc.properties)
                            )
                        }

                        schemaProperties
                    }

                    val schemasWithOneOf = deepListOfAllOfs.filter {
                        it.oneOf != null
                    }

                    val oneOfs = schemasWithOneOf.map { oneOfTheSchemas ->
                        val result = oneOfTheSchemas.oneOf.map {
                            val (componentName, schemaToProcess) = if(it.`$ref` != null) {
                                resolveReferenceToSchema(it.`$ref`)
                            } else {
                                "" to it
                            }
                            val requiredFields = schemaToProcess.required.orEmpty()
                            componentName to SchemaProperty(schemaToProcess.extensions.orEmpty(), toSchemaProperties(
                                schemaToProcess,
                                requiredFields,
                                componentName,
                                typeStack
                            ))
                        }.flatMap { (componentName, schemaProperty) ->
                            schemaProperties.map {
                                componentName to SchemaProperty(
                                    extensions = it.extensions.plus(schemaProperty.extensions),
                                    properties = combine(it.properties, schemaProperty.properties)
                                )
                            }
                        }

                        result
                    }.flatten().map { (componentName, schemaProperty) ->
                        toJSONObjectPattern(
                            schemaProperty.properties,
                            "(${componentName})",
                            schemaProperty.extensions
                        )
                    }

                    val pattern = if (oneOfs.size == 1)
                        oneOfs.single()
                    else if (oneOfs.size > 1)
                        AnyPattern(
                            oneOfs,
                            typeAlias = "(${patternName})",
                            extensions = emptyMap()
                        )
                    else if(allDiscriminators.isNotEmpty())
                        AnyPattern(
                            pattern = schemaProperties.zip(allDiscriminators.schemaNames)
                                .map { (schemaProperty, schemaName) ->
                                    toJSONObjectPattern(
                                        schemaProperty.properties,
                                        "(${schemaName})",
                                        schemaProperty.extensions
                                    )
                                },
                            discriminator = Discriminator.create(
                                allDiscriminators.key,
                                allDiscriminators.values.toSet(),
                                schema.discriminator?.mapping.orEmpty()
                            ),
                            typeAlias = "(${patternName})"
                        )
                    else if(schemaProperties.size > 1) {
                        val pattern = schemaProperties.map {
                            toJSONObjectPattern(it.properties, "(${patternName})", it.extensions)
                        }
                        AnyPattern(
                            pattern,
                            extensions = emptyMap()
                        )
                    }
                    else {
                        val schemaProperty = schemaProperties.single()
                        toJSONObjectPattern(
                            schemaProperty.properties,
                            "(${patternName})",
                            schemaProperty.extensions
                        )
                    }

                    cacheComponentPattern(patternName, pattern)

                    pattern
                } else if (schema.oneOf != null) {
                    val candidatePatterns = schema.oneOf.filterNot { nullableEmptyObject(it) }.map { componentSchema ->
                        val (componentName, schemaToProcess) =
                            if (componentSchema.`$ref` != null)
                                resolveReferenceToSchema(componentSchema.`$ref`)
                            else
                                "" to componentSchema

                        toSpecmaticPattern(schemaToProcess, typeStack.plus(componentName), componentName)
                    }

                    val nullable =
                        if (schema.oneOf.any { nullableEmptyObject(it) }) listOf(NullPattern) else emptyList()

                    val impliedDiscriminatorMappings = schema.oneOf.impliedDiscriminatorMappings()
                    val finalDiscriminatorMappings = schema.discriminator?.mapping.orEmpty().plus(impliedDiscriminatorMappings).distinctByValue()

                    AnyPattern(
                        candidatePatterns.plus(nullable),
                        typeAlias = "(${patternName})",
                        discriminator = Discriminator.create(schema.discriminator?.propertyName, finalDiscriminatorMappings.keys.toSet(), finalDiscriminatorMappings)
                    )
                } else if (schema.anyOf != null) {
                    throw UnsupportedOperationException("Specmatic does not support anyOf")
                } else {
                    throw UnsupportedOperationException("Unsupported composed schema: $schema")
                }
            }

            else -> {
                if (schema.nullable == true && schema.additionalProperties == null && schema.`$ref` == null) {
                    NullPattern
                } else if (schema.additionalProperties is Schema<*> || schema.additionalProperties == true || schema.properties != null) {
                    toJsonObjectPattern(schema, patternName, typeStack)
                } else {
                    val schemaFragment = if(patternName.isNotBlank()) " in schema $patternName" else " in the schema"

                    if(schema.javaClass.simpleName != "Schema")
                        throw ContractException("${schemaFragment.capitalizeFirstChar()} is not yet supported, please raise an issue on https://github.com/specmatic/specmatic/issues")
                    else
                        AnyNonNullJSONValue()
                }
            }
        }.also {
            when {
                it.instanceOf(JSONObjectPattern::class) && jsonInFormData -> {
                    PatternInStringPattern(
                        patterns.getOrDefault("($patternName)", StringPattern()), "($patternName)"
                    )
                }

                else -> it
            }
        }

        return when (schema.nullable) {
            false, null -> pattern
            true -> pattern.toNullable(schema.example?.toString())
        }
    }

    private fun numberPattern(schema: Schema<*>, isDoubleFormat: Boolean) = NumberPattern(
        minimum = schema.minimum,
        maximum = schema.maximum,
        exclusiveMinimum = schema.exclusiveMinimum ?: false,
        exclusiveMaximum = schema.exclusiveMaximum ?: false,
        isDoubleFormat = isDoubleFormat,
        example = schema.example?.toString()
    )

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
        return if (pattern1 !is AnyNonNullJSONValue && pattern2 is AnyNonNullJSONValue)
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
        return schema is ObjectSchema && schema.nullable == true
    }

    private fun toXMLPattern(mediaType: MediaType): Pattern {
        return toXMLPattern(mediaType.schema, typeStack = emptyList())
    }

    private fun toXMLPattern(
        schema: Schema<*>, nodeNameFromProperty: String? = null, typeStack: List<String>
    ): XMLPattern {
        val name = schema.xml?.name ?: nodeNameFromProperty

        return when (schema) {
            is ObjectSchema -> {
                if(schema.properties == null) {
                    throw ContractException("XML schema named $name does not have properties.")
                }

                val nodeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute != true
                }

                val nodes = nodeProperties.map { (propertyName: String, propertySchema) ->
                    val type = when (propertySchema.type) {
                        in primitiveOpenAPITypes -> {
                            val innerPattern = DeferredPattern(primitiveOpenAPITypes.getValue(propertySchema.type))
                            XMLPattern(XMLTypeData(propertyName, propertyName, emptyMap(), listOf(innerPattern)))
                        }

                        else -> {
                            toXMLPattern(propertySchema, propertyName, typeStack)
                        }
                    }

                    val optionalAttribute = if (propertyName !in (schema.required ?: emptyList<String>())) mapOf(
                        OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(OPTIONAL_ATTRIBUTE_VALUE))
                    )
                    else emptyMap()

                    type.copy(pattern = type.pattern.copy(attributes = optionalAttribute.plus(type.pattern.attributes)))
                }

                val attributeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute == true
                }

                val attributes: Map<String, Pattern> = attributeProperties.map { (name, schema) ->
                    val attributeName = if(name !in schema.required.orEmpty())
                        "$name.opt"
                    else
                        name

                    attributeName to toSpecmaticPattern(schema, emptyList())
                }.toMap()

                name ?: throw ContractException("Could not determine name for an xml node")

                val namespaceAttributes: Map<String, ExactValuePattern> =
                    if (schema.xml?.namespace != null && schema.xml?.prefix != null) {
                        val attributeName = "xmlns:${schema.xml?.prefix}"
                        val attributeValue = ExactValuePattern(StringValue(schema.xml.namespace))
                        mapOf(attributeName to attributeValue)
                    } else {
                        emptyMap()
                    }

                val xmlTypeData = XMLTypeData(name, realName(schema, name), namespaceAttributes.plus(attributes), nodes)

                XMLPattern(xmlTypeData)
            }

            is ArraySchema -> {
                val repeatingSchema = schema.items as Schema<Any>

                val repeatingType = when (repeatingSchema.type) {
                    in primitiveOpenAPITypes -> {
                        val innerPattern = DeferredPattern(primitiveOpenAPITypes.getValue(repeatingSchema.type))

                        val innerName = repeatingSchema.xml?.name
                            ?: if (schema.xml?.name != null && schema.xml?.wrapped == true) schema.xml.name else nodeNameFromProperty

                        XMLPattern(
                            XMLTypeData(
                                innerName ?: throw ContractException("Could not determine name for an xml node"),
                                innerName,
                                emptyMap(),
                                listOf(innerPattern)
                            )
                        )
                    }

                    else -> {
                        toXMLPattern(repeatingSchema, name, typeStack)
                    }
                }.let { repeatingType ->
                    repeatingType.copy(
                        pattern = repeatingType.pattern.copy(
                            attributes = repeatingType.pattern.attributes.plus(
                                OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(MULTIPLE_ATTRIBUTE_VALUE))
                            )
                        )
                    )
                }

                if (schema.xml?.wrapped == true) {
                    val wrappedName = schema.xml?.name ?: nodeNameFromProperty
                    val wrapperTypeData = XMLTypeData(
                        wrappedName ?: throw ContractException("Could not determine name for an xml node"),
                        wrappedName,
                        emptyMap(),
                        listOf(repeatingType)
                    )
                    XMLPattern(wrapperTypeData)
                } else repeatingType
            }

            else -> {
                if (schema.`$ref` != null) {
                    val component = schema.`$ref`
                    val (componentName, componentSchema) = resolveReferenceToSchema(component)

                    val typeName = "($componentName)"

                    val nodeName = componentSchema.xml?.name ?: name ?: componentName

                    if (typeName !in typeStack) {
                        val componentPattern = toXMLPattern(componentSchema, componentName, typeStack.plus(typeName))
                        cacheComponentPattern(componentName, componentPattern)
                    }

                    val xmlRefType = XMLTypeData(
                        nodeName, nodeName, mapOf(
                            TYPE_ATTRIBUTE_NAME to ExactValuePattern(
                                StringValue(
                                    componentName
                                )
                            )
                        ), emptyList()
                    )

                    XMLPattern(xmlRefType)
                } else throw ContractException("Node not recognized as XML type: ${schema.type}")
            }
        }
    }

    private fun realName(schema: ObjectSchema, name: String): String = if (schema.xml?.prefix != null) {
        "${schema.xml?.prefix}:${name}"
    } else {
        name
    }

    private val primitiveOpenAPITypes =
        mapOf("string" to "(string)", "number" to "(number)", "integer" to "(number)", "boolean" to "(boolean)")

    private fun toJsonObjectPattern(
        schema: Schema<*>, patternName: String, typeStack: List<String>
    ): JSONObjectPattern {
        val requiredFields = schema.required.orEmpty()
        val schemaProperties = toSchemaProperties(schema, requiredFields, patternName, typeStack)
        val minProperties: Int? = schema.minProperties
        val maxProperties: Int? = schema.maxProperties
        val jsonObjectPattern = toJSONObjectPattern(schemaProperties, if(patternName.isNotBlank()) "(${patternName})" else null).copy(
            minProperties = minProperties,
            maxProperties = maxProperties,
            additionalProperties = additionalPropertiesFrom(schema, patternName, typeStack),
            extensions = schema.extensions.orEmpty()
        )
        return cacheComponentPattern(patternName, jsonObjectPattern)
    }

    private fun additionalPropertiesFrom(
        schema: Schema<*>, patternName: String, typeStack: List<String>
    ): AdditionalProperties {
        val schemaProperties = schema.properties.orEmpty()

        val additionalProperties = schema.additionalProperties ?: return when {
            schemaProperties.isEmpty() -> AdditionalProperties.FreeForm
            else -> AdditionalProperties.NoAdditionalProperties
        }

        return when (additionalProperties) {
            true -> AdditionalProperties.FreeForm
            false -> AdditionalProperties.NoAdditionalProperties
            is Schema<*> -> processAdditionalPropertiesSchema(additionalProperties, patternName, typeStack)
            else -> throw ContractException(
                breadCrumb = "$patternName.additionalProperties",
                errorMessage = "Unrecognized type for additionalProperties: expected a boolean or a schema"
            )
        }
    }

    private fun processAdditionalPropertiesSchema(schema: Schema<*>, patternName: String, typeStack: List<String>): AdditionalProperties {
        val parsedPattern = toSpecmaticPattern(schema, typeStack, patternName)
        return if (parsedPattern is AnyNonNullJSONValue) AdditionalProperties.FreeForm
        else AdditionalProperties.PatternConstrained(parsedPattern)
    }

    private fun toSchemaProperties(
        schema: Schema<*>, requiredFields: List<String>, patternName: String, typeStack: List<String>, discriminatorDetails: DiscriminatorDetails = DiscriminatorDetails()
    ): Map<String, Pattern> {
        val patternMap = schema.properties.orEmpty().map { (propertyName, propertyType) ->
            if (schema.discriminator?.propertyName == propertyName)
                propertyName to ExactValuePattern(StringValue(patternName), discriminator = true)
            else if (discriminatorDetails.hasValueForKey(propertyName)) {
                propertyName to discriminatorDetails.valueFor(propertyName)
            } else {
                val optional = !requiredFields.contains(propertyName)
                toSpecmaticParamName(optional, propertyName) to attempt(breadCrumb = propertyName) {
                    toSpecmaticPattern(
                        propertyType,
                        typeStack) }
            }
        }.toMap()

        return patternMap
    }

    private fun toEnum(schema: Schema<*>, patternName: String, toSpecmaticValue: (Any) -> Value): EnumPattern {
        val specmaticValues = schema.enum.map<Any?, Value> { enumValue ->
            when (enumValue) {
                null -> NullValue
                else -> toSpecmaticValue(enumValue)
            }
        }

        if (schema.nullable != true && NullValue in specmaticValues)
            throw ContractException("Enum values cannot contain null since the schema $patternName is not nullable")

        if (schema.nullable == true && NullValue !in specmaticValues)
            throw ContractException("Enum values must contain null since the schema $patternName is nullable")

        return EnumPattern(specmaticValues, nullable = schema.nullable == true, typeAlias = patternName).also {
            cacheComponentPattern(patternName, it)
        }
    }

    private fun toSpecmaticParamName(optional: Boolean, name: String) = when (optional) {
        true -> "${name}?"
        false -> name
    }

    private fun resolveReferenceToSchema(component: String): Pair<String, Schema<Any>> {
        val componentName = extractComponentName(component)
        val components = parsedOpenApi.components ?: throw ContractException("Could not find components in the specification (trying to dereference $component")
        val schemas = components.schemas ?: throw ContractException("Could not find schemas components in the specification (trying to dereference $component)")

        val schema =
            schemas[componentName] ?: ObjectSchema().also { it.properties = emptyMap() }

        return componentName to schema as Schema<Any>
    }

    private fun resolveReferenceToRequestBody(component: String): Pair<String, RequestBody> {
        val componentName = extractComponentName(component)
        val requestBody = parsedOpenApi.components.requestBodies[componentName] ?: RequestBody()

        return componentName to requestBody
    }

    private fun extractComponentName(component: String): String {
        if(!component.startsWith("#")) {
            val componentPath = component.substringAfterLast("#")
            val filePath = component.substringBeforeLast("#")
            val message = try {
                "Could not dereference $component. Either the file $filePath does not exist, or $componentPath is missing from it."
            } catch (e: Throwable) {
                "Could not dereference $component due an an error (${e.message})."
            }

            throw ContractException(message)
        }

        return componentNameFromReference(component)
    }

    private fun componentNameFromReference(component: String) = component.substringAfterLast("/")

    private fun toSpecmaticQueryParam(operation: Operation): HttpQueryParamPattern {
        val parameters = operation.parameters ?: return HttpQueryParamPattern(emptyMap())

        val queryPattern: Map<String, Pattern> = parameters.filterIsInstance<QueryParameter>().associate {
            logger.debug("Processing query parameter ${it.name}")

            val specmaticPattern: Pattern? = if (it.schema.type == "array") {
                QueryParameterArrayPattern(listOf(toSpecmaticPattern(schema = it.schema.items, typeStack = emptyList())), it.name)
            } else if (it.schema.type != "object") {
                QueryParameterScalarPattern(toSpecmaticPattern(schema = it.schema, typeStack = emptyList(), patternName = it.name))
            } else null

            val queryParamKey = if(it.required == true)
                it.name
            else
                "${it.name}?"

            queryParamKey to specmaticPattern
        }.filterValues { it != null }.mapValues { it.value!! }

        val additionalProperties = additionalPropertiesInQueryParam(parameters)

        return HttpQueryParamPattern(queryPattern, additionalProperties)
    }

    private fun additionalPropertiesInQueryParam(parameters: List<Parameter>): Pattern? {
        val additionalProperties = parameters.filterIsInstance<QueryParameter>()
            .find { it.schema.type == "object" && it.schema.additionalProperties != null }?.schema?.additionalProperties

        if(additionalProperties == false)
            return null

        if(additionalProperties == true)
            return AnythingPattern

        if(additionalProperties is Schema<*>)
            return toSpecmaticPattern(additionalProperties, emptyList())

        return null
    }

    private fun toSpecmaticPathParam(openApiPath: String, operation: Operation): HttpPathPattern {
        val parameters = operation.parameters ?: emptyList()

        val pathSegments: List<String> = openApiPath.removePrefix("/").removeSuffix("/").let {
            if (it.isBlank())
                emptyList()
            else it.split("/")
        }
        val pathParamMap: Map<String, PathParameter> = parameters.filterIsInstance<PathParameter>().associateBy {
                it.name
            }

        val pathPattern = pathSegments.mapIndexed { index, pathSegment ->
            logger.debug("Processing path segment $pathSegment")

            if (!isParameter(pathSegment)) {
                return@mapIndexed URLPathSegmentPattern(ExactValuePattern(StringValue(pathSegment)))
            }

            val paramName = pathSegment.removeSurrounding("{", "}")
            val param = pathParamMap[paramName] ?: throw ContractException(
                errorMessage = "The path parameter in $openApiPath is not defined in the specification"
            )

            val pathSoFar = pathSegments.take(index + 1).joinToString(separator = "/")
            val conflicts = pathTree.conflictsFor(pathSoFar)
            URLPathSegmentPattern(
                pattern = toSpecmaticPattern(param.schema, emptyList()),
                key = paramName,
                conflicts = conflicts
            )
        }

        val specmaticPath = toSpecmaticFormattedPathString(parameters, openApiPath)

        return HttpPathPattern(pathPattern, specmaticPath)
    }

    private fun isParameter(pathSegment: String) = pathSegment.startsWith("{") && pathSegment.endsWith("}")

    private fun toSpecmaticFormattedPathString(
        parameters: List<Parameter>,
        openApiPath: String
    ): String {
        return parameters.filterIsInstance<PathParameter>().foldRight(openApiPath) { it, specmaticPath ->
            val pattern = if (it.schema.enum != null) StringPattern("") else toSpecmaticPattern(it.schema, emptyList())
            specmaticPath.replace(
                "{${it.name}}", "(${it.name}:${pattern.typeName})"
            )
        }
    }

    private fun openApiOperations(pathItem: PathItem): Map<String, OpenApiOperation> {
        return linkedMapOf<String, Operation?>(
            "POST" to pathItem.post,
            "GET" to pathItem.get,
            "PATCH" to pathItem.patch,
            "PUT" to pathItem.put,
            "DELETE" to pathItem.delete
        ).filter { (_, value) -> value != null }.map { (key, value) -> key to OpenApiOperation(value!!) }.toMap()
    }
}
