package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.Configuration.Companion.configFilePath
import io.specmatic.core.SourceProvider.filesystem
import io.specmatic.core.SourceProvider.git
import io.specmatic.core.SourceProvider.web
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_1
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.config.v3.Consumes
import io.specmatic.core.config.v3.ConsumesDeserializer
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_SCHEMA
import io.specmatic.core.utilities.Flags.Companion.ONLY_POSITIVE
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_STUB_DELAY
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_TIMEOUT
import io.specmatic.core.utilities.Flags.Companion.VALIDATE_RESPONSE_VALUE
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue
import io.specmatic.core.utilities.Flags.Companion.getLongValue
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import io.specmatic.core.utilities.GitMonoRepo
import io.specmatic.core.utilities.GitRepo
import io.specmatic.core.utilities.LocalFileSystemSource
import io.specmatic.core.utilities.WebSource
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.readEnvVarOrProperty
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import java.io.File

const val APPLICATION_NAME = "Specmatic"
const val APPLICATION_NAME_LOWER_CASE = "specmatic"
const val CONFIG_FILE_NAME_WITHOUT_EXT = "specmatic"
const val DEFAULT_TIMEOUT_IN_MILLISECONDS: Long = 6000L
const val CONTRACT_EXTENSION = "spec"
const val YAML = "yaml"
const val WSDL = "wsdl"
const val YML = "yml"
const val JSON = "json"
val CONFIG_EXTENSIONS = listOf(YAML, YML, JSON)
val OPENAPI_FILE_EXTENSIONS = listOf(YAML, YML, JSON)
val CONTRACT_EXTENSIONS = listOf(CONTRACT_EXTENSION, WSDL) + OPENAPI_FILE_EXTENSIONS
const val DATA_DIR_SUFFIX = "_data"
const val TEST_DIR_SUFFIX = "_tests"
const val EXAMPLES_DIR_SUFFIX = "_examples"
const val SPECMATIC_GITHUB_ISSUES = "https://github.com/znsio/specmatic/issues"
const val DEFAULT_WORKING_DIRECTORY = ".$APPLICATION_NAME_LOWER_CASE"

const val SPECMATIC_STUB_DICTIONARY = "SPECMATIC_STUB_DICTIONARY"

const val MISSING_CONFIG_FILE_MESSAGE = "Config file does not exist. (Could not find file ./specmatic.json OR ./specmatic.yaml OR ./specmatic.yml)"

class WorkingDirectory(private val filePath: File) {
    constructor(path: String = DEFAULT_WORKING_DIRECTORY): this(File(path))

    val path: String
        get() {
            return filePath.path
        }
}

fun invalidContractExtensionMessage(filename: String): String {
    return "The file $filename does not seem like a contract file. Valid extensions for contract files are ${CONTRACT_EXTENSIONS.joinToString(", ")}"
}

fun String.isContractFile(): Boolean {
    return File(this).extension in CONTRACT_EXTENSIONS
}

fun String.loadContract(): Feature {
    if(!this.isContractFile())
        throw ContractException(invalidContractExtensionMessage(this))

    return parseContractFileToFeature(File(this))
}

data class StubConfiguration(
    private val generative: Boolean? = null,
    private val delayInMilliseconds: Long? = null,
    private val dictionary: String? = null,
    private val includeMandatoryAndRequestedKeysInResponse: Boolean? = null
) {
    fun getGenerative(): Boolean? {
        return generative
    }

    fun getDelayInMilliseconds(): Long? {
        return delayInMilliseconds ?: getLongValue(SPECMATIC_STUB_DELAY)
    }

    fun getDictionary(): String? {
        return dictionary ?: getStringValue(SPECMATIC_STUB_DICTIONARY)
    }

    fun getIncludeMandatoryAndRequestedKeysInResponse(): Boolean? {
        return includeMandatoryAndRequestedKeysInResponse
    }
}

data class VirtualServiceConfiguration(
    private val nonPatchableKeys: Set<String> = emptySet()
) {
    fun getNonPatchableKeys(): Set<String> {
        return nonPatchableKeys
    }
}

data class WorkflowIDOperation(
    val extract: String? = null,
    val use: String? = null
)

interface WorkflowDetails {
    fun getExtractForAPI(apiDescription: String): String?
    fun getUseForAPI(apiDescription: String): String?

    companion object {
        val default: WorkflowDetails = WorkflowConfiguration()
    }
}

data class WorkflowConfiguration(
    private val ids: Map<String, WorkflowIDOperation> = emptyMap()
) : WorkflowDetails {
    private fun getOperation(operationId: String): WorkflowIDOperation? {
        return ids[operationId]
    }

    override fun getExtractForAPI(apiDescription: String): String? {
        return getOperation(apiDescription)?.extract
    }

    override fun getUseForAPI(apiDescription: String): String? {
        val operation = getOperation(apiDescription) ?: getOperation("*")
        return operation?.use
    }
}

interface AttributeSelectionPatternDetails {
    fun getDefaultFields(): List<String>
    fun getQueryParamKey(): String

    companion object {
        val default: AttributeSelectionPatternDetails = AttributeSelectionPattern()
    }
}

data class AttributeSelectionPattern(
    @field:JsonAlias("default_fields")
    private val defaultFields: List<String>? = null,
    @field:JsonAlias("query_param_key")
    private val queryParamKey: String? = null
) : AttributeSelectionPatternDetails {
    override fun getDefaultFields(): List<String> {
        return defaultFields ?: readEnvVarOrProperty(
            ATTRIBUTE_SELECTION_DEFAULT_FIELDS,
            ATTRIBUTE_SELECTION_DEFAULT_FIELDS
        ).orEmpty().split(",").filter { it.isNotBlank() }
    }

    override fun getQueryParamKey(): String {
        return queryParamKey ?: readEnvVarOrProperty(
            ATTRIBUTE_SELECTION_QUERY_PARAM_KEY,
            ATTRIBUTE_SELECTION_QUERY_PARAM_KEY
        ).orEmpty()
    }
}

data class SpecmaticConfig(
    private val sources: List<Source>? = null,
    private val auth: Auth? = null,
    private val pipeline: Pipeline? = null,
    private val environments: Map<String, Environment>? = null,
    private val hooks: Map<String, String> = emptyMap(),
    private val repository: RepositoryInfo? = null,
    private val report: ReportConfigurationDetails? = null,
    private val security: SecurityConfiguration? = null,
    private val test: TestConfiguration? = TestConfiguration(),
    private val stub: StubConfiguration = StubConfiguration(),
    private val virtualService: VirtualServiceConfiguration = VirtualServiceConfiguration(),
    private val examples: List<String>? = null,
    private val workflow: WorkflowConfiguration? = null,
    private val ignoreInlineExamples: Boolean? = null,
    private val additionalExampleParamsFilePath: String? = null,
    private val attributeSelectionPattern: AttributeSelectionPattern = AttributeSelectionPattern(),
    private val allPatternsMandatory: Boolean? = null,
    private val defaultPatternValues: Map<String, Any> = emptyMap(),
    private val version: SpecmaticConfigVersion? = null
) {
    companion object {
        fun getReport(specmaticConfig: SpecmaticConfig): ReportConfigurationDetails? {
            return specmaticConfig.report
        }

        @JsonIgnore
        fun getSources(specmaticConfig: SpecmaticConfig): List<Source>? {
            return specmaticConfig.sources
        }

        @JsonIgnore
        fun getRepository(specmaticConfig: SpecmaticConfig): RepositoryInfo? {
            return specmaticConfig.repository
        }

        @JsonIgnore
        fun getPipeline(specmaticConfig: SpecmaticConfig): Pipeline? {
            return specmaticConfig.pipeline
        }

        @JsonIgnore
        fun getSecurityConfiguration(specmaticConfig: SpecmaticConfig?): SecurityConfiguration? {
            return specmaticConfig?.security
        }

        @JsonIgnore
        fun getWorkflowConfiguration(specmaticConfig: SpecmaticConfig): WorkflowConfiguration? {
            return specmaticConfig.workflow
        }

        @JsonIgnore
        fun getTestConfiguration(specmaticConfig: SpecmaticConfig): TestConfiguration? {
            return specmaticConfig.test
        }

        @JsonIgnore
        fun getVirtualServiceConfiguration(specmaticConfig: SpecmaticConfig): VirtualServiceConfiguration {
            return specmaticConfig.virtualService
        }

        @JsonIgnore
        fun getAllPatternsMandatory(specmaticConfig: SpecmaticConfig): Boolean? {
            return specmaticConfig.allPatternsMandatory
        }

        @JsonIgnore
        fun getIgnoreInlineExamples(specmaticConfig: SpecmaticConfig): Boolean? {
            return specmaticConfig.ignoreInlineExamples
        }

        @JsonIgnore
        fun getAttributeSelectionPattern(specmaticConfig: SpecmaticConfig): AttributeSelectionPattern {
            return specmaticConfig.attributeSelectionPattern
        }

        @JsonIgnore
        fun getStubConfiguration(specmaticConfig: SpecmaticConfig): StubConfiguration {
            return specmaticConfig.stub
        }

        fun getEnvironments(specmaticConfig: SpecmaticConfig): Map<String, Environment>? {
            return specmaticConfig.environments
        }
    }

    @JsonIgnore
    fun getReport(): ReportConfiguration? {
        return report
    }

    @JsonIgnore
    fun getWorkflowDetails(): WorkflowDetails? {
        return workflow
    }

    @JsonIgnore
    fun getAttributeSelectionPattern(): AttributeSelectionPatternDetails {
        return attributeSelectionPattern
    }

    @JsonIgnore
    fun specToStubPortMap(defaultPort: Int, relativeTo: File = File(".")): Map<String, Int> {
        return sources?.flatMap { it.specToStubPortMap(defaultPort, relativeTo).entries }
            ?.associate { it.key to it.value }
            ?: emptyMap()
    }

    @JsonIgnore
    fun stubPorts(defaultPort: Int): List<Int> {
        return sources?.flatMap {
            it.stub.orEmpty().map { consumes ->
                when(consumes) {
                    is Consumes.StringValue -> defaultPort
                    is Consumes.ObjectValue -> consumes.port
                }
            }
        }?.plus(defaultPort)?.distinct() ?: emptyList()
    }

    fun logDependencyProjects(azure: AzureAPI) {
        logger.log("Dependency projects")
        logger.log("-------------------")

        sources?.forEach { source ->
            logger.log("In central repo ${source.repository}")

            source.test?.forEach { relativeContractPath ->
                logger.log("  Consumers of $relativeContractPath")
                val consumers = azure.referencesToContract(relativeContractPath)

                if (consumers.isEmpty()) {
                    logger.log("    ** no consumers found **")
                } else {
                    consumers.forEach {
                        logger.log("  - ${it.description}")
                    }
                }

                logger.newLine()
            }
        }
    }


    @JsonIgnore
    fun loadSources(): List<ContractSource> {
        return sources?.map { source ->
            when (source.provider) {
                git -> {
                    val stubPaths = source.specsUsedAsStub()
                    val testPaths = source.test ?: emptyList()

                    when (source.repository) {
                        null -> GitMonoRepo(testPaths, stubPaths, source.provider.toString())
                        else -> GitRepo(source.repository, source.branch, testPaths, stubPaths, source.provider.toString())
                    }
                }

                filesystem -> {
                    val stubPaths = source.specsUsedAsStub()
                    val testPaths = source.test ?: emptyList()

                    LocalFileSystemSource(source.directory ?: ".", testPaths, stubPaths)
                }

                web -> {
                    val stubPaths = source.specsUsedAsStub()
                    val testPaths = source.test ?: emptyList()

                    WebSource(testPaths, stubPaths)
                }
            }
        } ?: emptyList()
    }

    @JsonIgnore
    fun attributeSelectionQueryParamKey(): String {
        return attributeSelectionPattern.getQueryParamKey()
    }

    @JsonIgnore
    fun isExtensibleSchemaEnabled(): Boolean {
        return test?.getAllowExtensibleSchema() ?: getBooleanValue(EXTENSIBLE_SCHEMA)
    }

    @JsonIgnore
    fun isResiliencyTestingEnabled(): Boolean {
        return (getResiliencyTestsEnabled() != ResiliencyTestSuite.none)
    }

    @JsonIgnore
    fun isOnlyPositiveTestingEnabled(): Boolean {
        return (getResiliencyTestsEnabled() == ResiliencyTestSuite.positiveOnly)
    }

    @JsonIgnore
    fun isResponseValueValidationEnabled(): Boolean {
        return test?.getValidateResponseValues() ?: getBooleanValue(VALIDATE_RESPONSE_VALUE)
    }

    @JsonIgnore
    fun parsedDefaultPatternValues(): Map<String, Value> {
        return parsedJSONObject(ObjectMapper().writeValueAsString(defaultPatternValues)).jsonObject
    }

    @JsonIgnore
    fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        return test?.getResiliencyTests()?.getEnableTestSuite() ?: ResiliencyTestSuite.none
    }

    @JsonIgnore
    fun getTestTimeoutInMilliseconds(): Long? {
        return test?.getTimeoutInMilliseconds()
    }

    @JsonIgnore
    fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig {
        return this.copy(
            test = test?.copy(
                resiliencyTests = test.getResiliencyTests().copy(
                    enable = if (onlyPositive) ResiliencyTestSuite.positiveOnly else ResiliencyTestSuite.all
                )
            )
        )
    }

    @JsonIgnore
    fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        return stub.getIncludeMandatoryAndRequestedKeysInResponse() ?: true
    }

    @JsonIgnore
    fun getStubGenerative(): Boolean {
        return stub.getGenerative() ?: false
    }

    @JsonIgnore
    fun getStubDelayInMilliseconds(): Long? {
        return stub.getDelayInMilliseconds()
    }

    @JsonIgnore
    fun getStubDictionary(): String? {
        return stub.getDictionary()
    }

    @JsonIgnore
    fun getIgnoreInlineExamples(): Boolean {
        return ignoreInlineExamples ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES)
    }

    @JsonIgnore
    fun getAllPatternsMandatory(): Boolean {
        return allPatternsMandatory ?: getBooleanValue(Flags.ALL_PATTERNS_MANDATORY)
    }

    @JsonIgnore
    fun getAdditionalExampleParamsFilePath(): String? {
        return additionalExampleParamsFilePath ?: getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)
    }

    @JsonIgnore
    fun getHooks(): Map<String, String> {
        return hooks
    }

    @JsonIgnore
    fun getDefaultPatternValues(): Map<String, Any> {
        return defaultPatternValues
    }

    fun getVersion(): SpecmaticConfigVersion {
        return this.version ?: VERSION_1
    }

    @JsonIgnore
    fun getAuth(): Auth? {
        return auth
    }

    @JsonIgnore
    fun getAuthBearerFile(): String? {
        return auth?.getBearerFile()
    }

    @JsonIgnore
    fun getAuthBearerEnvironmentVariable(): String? {
        return auth?.getBearerEnvironmentVariable()
    }

    @JsonIgnore
    fun getExamples(): List<String> {
        return examples ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    fun getRepositoryProvider(): String? {
        return repository?.getProvider()
    }

    @JsonIgnore
    fun getRepositoryCollectionName(): String? {
        return repository?.getCollectionName()
    }

    @JsonIgnore
    fun getPipelineProvider(): PipelineProvider? {
        return pipeline?.getProvider()
    }

    @JsonIgnore
    fun getPipelineDefinitionId(): Int? {
        return pipeline?.getDefinitionId()
    }

    @JsonIgnore
    fun getPipelineOrganization(): String? {
        return pipeline?.getOrganization()
    }

    @JsonIgnore
    fun getPipelineProject(): String? {
        return pipeline?.getProject()
    }

    @JsonIgnore
    fun getOpenAPISecurityConfigurationScheme(scheme: String): SecuritySchemeConfiguration? {
        return security?.getOpenAPISecurityScheme(scheme)
    }

    @JsonIgnore
    fun getVirtualServiceNonPatchableKeys(): Set<String> {
        return virtualService.getNonPatchableKeys()
    }

    @JsonIgnore
    fun stubContracts(relativeTo: File = File(".")): List<String> {
        return sources?.flatMap { source ->
            source.stub.orEmpty().flatMap { stub ->
                when (stub) {
                    is Consumes.StringValue -> listOf(stub.value)
                    is Consumes.ObjectValue -> stub.specs
                }
            }.map { spec ->
                if (source.provider == web) spec
                else spec.canonicalPath(relativeTo)
            }
        } ?: emptyList()
    }

    @JsonIgnore
    private fun String.canonicalPath(relativeTo: File): String {
        return relativeTo.parentFile?.resolve(this)?.canonicalPath ?: File(this).canonicalPath
    }

    fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig {
        val reportConfigurationDetails = reportConfiguration as? ReportConfigurationDetails ?: return this
        return this.copy(report = reportConfigurationDetails)
    }

    fun getEnvironment(envName: String): JSONObjectValue {
        val envConfigFromFile = environments?.get(envName) ?: return JSONObjectValue()

        try {
            return parsedJSONObject(content = ObjectMapper().writeValueAsString(envConfigFromFile))
        } catch(e: Throwable) {
            throw ContractException("Error loading Specmatic configuration: ${e.message}")
        }
    }
}

data class TestConfiguration(
    private val resiliencyTests: ResiliencyTestsConfig? = null,
    private val validateResponseValues: Boolean? = null,
    private val allowExtensibleSchema: Boolean? = null,
    private val timeoutInMilliseconds: Long? = null
) {
    fun getResiliencyTests(): ResiliencyTestsConfig {
        return resiliencyTests ?: ResiliencyTestsConfig(
            isResiliencyTestFlagEnabled = getBooleanValue(SPECMATIC_GENERATIVE_TESTS),
            isOnlyPositiveFlagEnabled = getBooleanValue(ONLY_POSITIVE)
        )
    }

    fun getValidateResponseValues(): Boolean? {
        return validateResponseValues
    }

    fun getAllowExtensibleSchema(): Boolean? {
        return allowExtensibleSchema
    }

    fun getTimeoutInMilliseconds(): Long? {
        return timeoutInMilliseconds ?: getLongValue(SPECMATIC_TEST_TIMEOUT)
    }
}

enum class ResiliencyTestSuite {
    all, positiveOnly, none
}

data class ResiliencyTestsConfig(
    private val enable: ResiliencyTestSuite? = null
) {
    constructor(isResiliencyTestFlagEnabled: Boolean, isOnlyPositiveFlagEnabled: Boolean) : this(
        enable = getEnableFrom(isResiliencyTestFlagEnabled, isOnlyPositiveFlagEnabled)
    )

    fun getEnableTestSuite(): ResiliencyTestSuite? {
        return enable
    }

    companion object {
        private fun getEnableFrom(
            isResiliencyTestFlagEnabled: Boolean,
            isOnlyPositiveFlagEnabled: Boolean
        ): ResiliencyTestSuite? {
            return when {
                isResiliencyTestFlagEnabled -> ResiliencyTestSuite.all
                isOnlyPositiveFlagEnabled -> ResiliencyTestSuite.positiveOnly
                else -> null
            }
        }
    }
}

data class Auth(
    @JsonProperty("bearer-file") private val bearerFile: String = "bearer.txt",
    @JsonProperty("bearer-environment-variable") private val bearerEnvironmentVariable: String? = null
) {
    fun getBearerFile(): String {
        return bearerFile
    }

    fun getBearerEnvironmentVariable(): String? {
        return bearerEnvironmentVariable
    }
}

enum class PipelineProvider { azure }

data class Pipeline(
    private val provider: PipelineProvider = PipelineProvider.azure,
    private val organization: String = "",
    private val project: String = "",
    private val definitionId: Int = 0
) {
    fun getProvider(): PipelineProvider {
        return provider
    }

    fun getOrganization(): String {
        return organization
    }

    fun getProject(): String {
        return project
    }

    fun getDefinitionId(): Int {
        return definitionId
    }
}

data class Environment(
    val baseurls: Map<String, String>? = null,
    val variables: Map<String, String>? = null
)

enum class SourceProvider { git, filesystem, web }

data class Source(
    @field:JsonAlias("type")
    val provider: SourceProvider = filesystem,
    val repository: String? = null,
    val branch: String? = null,
    @field:JsonAlias("provides")
    val test: List<String>? = null,
    @field:JsonAlias("consumes")
    @JsonDeserialize(using = ConsumesDeserializer::class)
    val stub: List<Consumes>? = null,
    val directory: String? = null,
) {
    constructor(test: List<String>? = null, stub: List<String>? = null) : this(
        test = test,
        stub = stub?.map { Consumes.StringValue(it) }
    )

    fun specsUsedAsStub(): List<String> {
        return stub.orEmpty().flatMap {
            when (it) {
                is Consumes.StringValue -> listOf(it.value)
                is Consumes.ObjectValue -> it.specs
            }
        }
    }

    fun specToStubPortMap(defaultPort: Int, relativeTo: File = File(".")): Map<String, Int> {
        return stub.orEmpty().flatMap {
            when (it) {
                is Consumes.StringValue -> listOf(it.value.canonicalPath(relativeTo) to defaultPort)
                is Consumes.ObjectValue -> it.specs.map { specPath ->
                    specPath.canonicalPath(relativeTo) to it.port
                }
            }
        }.toMap()
    }

    private fun String.canonicalPath(relativeTo: File): String {
        if (provider == web) return this
        return relativeTo.parentFile?.resolve(this)?.canonicalPath ?: File(this).canonicalPath
    }
}

data class RepositoryInfo(
    private val provider: String,
    private val collectionName: String
) {
    fun getProvider(): String {
        return provider
    }

    fun getCollectionName(): String {
        return collectionName
    }
}


interface ReportConfiguration {
    fun withDefaultFormattersIfMissing(): ReportConfiguration
    fun getHTMLFormatter(): ReportFormatterDetails?
    fun getSuccessCriteria(): SuccessCriteria
    fun <T> mapRenderers(fn: (ReportFormatterType) -> T): List<T>
    fun excludedOpenAPIEndpoints(): List<String>
}

data class ReportConfigurationDetails(
    val formatters: List<ReportFormatterDetails>? = null,
    val types: ReportTypes = ReportTypes()
) : ReportConfiguration {
    companion object {
        val default = ReportConfigurationDetails(
            formatters = listOf(
                ReportFormatterDetails(ReportFormatterType.TEXT, ReportFormatterLayout.TABLE),
                ReportFormatterDetails(ReportFormatterType.HTML)
            ), types = ReportTypes()
        )

        fun getFormatters(report: ReportConfigurationDetails?): List<ReportFormatterDetails>? {
            return report?.formatters
        }

        fun getTypes(report: ReportConfigurationDetails?): ReportTypes? {
            return report?.types
        }
    }

    override fun withDefaultFormattersIfMissing(): ReportConfigurationDetails {
        val htmlReportFormatter = formatters?.firstOrNull {
            it.type == ReportFormatterType.HTML
        } ?: ReportFormatterDetails(ReportFormatterType.HTML)
        val textReportFormatter = formatters?.firstOrNull {
            it.type == ReportFormatterType.TEXT
        } ?: ReportFormatterDetails(ReportFormatterType.TEXT)

        return this.copy(formatters = listOf(htmlReportFormatter, textReportFormatter))
    }

    override fun getHTMLFormatter(): ReportFormatterDetails? {
        return formatters?.firstOrNull { it.type == ReportFormatterType.HTML }
    }

    override fun getSuccessCriteria(): SuccessCriteria {
        return types.apiCoverage.openAPI.successCriteria ?: SuccessCriteria()
    }

    override fun <T> mapRenderers(fn: (ReportFormatterType) -> T): List<T> {
        return formatters!!.map {
            fn(it.type)
        }
    }

    override fun excludedOpenAPIEndpoints(): List<String> {
        return types.apiCoverage.openAPI.excludedEndpoints
    }
}

interface ReportFormatter {
    var type: ReportFormatterType
    val layout: ReportFormatterLayout
    val lite: Boolean
    val title: String
    val logo: String
    val logoAltText: String
    val heading: String
    val outputDirectory: String
}

data class ReportFormatterDetails (
    override var type: ReportFormatterType = ReportFormatterType.TEXT,
    override val layout: ReportFormatterLayout = ReportFormatterLayout.TABLE,
    override val lite: Boolean = false,
    override val title: String = "Specmatic Report",
    override val logo: String = "assets/specmatic-logo.svg",
    override val logoAltText: String = "Specmatic",
    override val heading: String = "Contract Test Results",
    override val outputDirectory: String = "./build/reports/specmatic/html"
) : ReportFormatter

enum class ReportFormatterType {
    @JsonProperty("text")
    TEXT,

    @JsonProperty("html")
    HTML
}

enum class ReportFormatterLayout {
    @JsonProperty("table")
    TABLE
}

data class ReportTypes (
    @JsonProperty("APICoverage")
    val apiCoverage: APICoverage = APICoverage()
)

data class APICoverage (
    @JsonProperty("OpenAPI")
    val openAPI: APICoverageConfiguration = APICoverageConfiguration()
)

data class APICoverageConfiguration(
    val successCriteria: SuccessCriteria? = null,
    val excludedEndpoints: List<String> = emptyList()
)

data class SuccessCriteria(
    val minThresholdPercentage: Int = 0,
    val maxMissedEndpointsInSpec: Int = 0,
    val enforce: Boolean = false
)

data class SecurityConfiguration(
    @JsonProperty("OpenAPI")
    private val OpenAPI: OpenAPISecurityConfiguration?
) {
    fun getOpenAPISecurityScheme(scheme: String): SecuritySchemeConfiguration? {
        return OpenAPI?.securitySchemes?.get(scheme)
    }
}

data class OpenAPISecurityConfiguration(
    val securitySchemes: Map<String, SecuritySchemeConfiguration> = emptyMap()
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OAuth2SecuritySchemeConfiguration::class, name = "oauth2"),
    JsonSubTypes.Type(value = BasicAuthSecuritySchemeConfiguration::class, name = "basicAuth"),
    JsonSubTypes.Type(value = BearerSecuritySchemeConfiguration::class, name = "bearer"),
    JsonSubTypes.Type(value = APIKeySecuritySchemeConfiguration::class, name = "apiKey")
)
sealed class SecuritySchemeConfiguration {
    abstract val type: String
}

interface SecuritySchemeWithOAuthToken {
    val token: String
}

@JsonTypeName("oauth2")
data class OAuth2SecuritySchemeConfiguration(
    override val type: String = "oauth2",
    override val token: String = ""
) : SecuritySchemeConfiguration(), SecuritySchemeWithOAuthToken

@JsonTypeName("basicAuth")
data class BasicAuthSecuritySchemeConfiguration(
    override val type: String = "basicAuth",
    val token: String = ""
) : SecuritySchemeConfiguration()

@JsonTypeName("bearer")
data class BearerSecuritySchemeConfiguration(
    override val type: String = "bearer",
    override val token: String = ""
) : SecuritySchemeConfiguration(), SecuritySchemeWithOAuthToken

@JsonTypeName("apiKey")
data class APIKeySecuritySchemeConfiguration(
    override val type: String = "apiKey",
    val value: String = ""
) : SecuritySchemeConfiguration()

fun loadSpecmaticConfigOrDefault(configFileName: String? = null): SpecmaticConfig {
    return if(configFileName == null)
        SpecmaticConfig()
    else try {
        loadSpecmaticConfig(configFileName)
    }
    catch (e: ContractException) {
        logger.log(exceptionCauseMessage(e))
        SpecmaticConfig()
    }
}

fun loadSpecmaticConfig(configFileName: String? = null): SpecmaticConfig {
    val configFile = File(configFileName ?: configFilePath)
    if (!configFile.exists()) {
        throw ContractException("Could not find the Specmatic configuration at path ${configFile.canonicalPath}")
    }
    try {
        return configFile.toSpecmaticConfig()
    } catch(e: LinkageError) {
        logger.log(e, "A dependency version conflict has been detected. If you are using Spring in a maven project, a common resolution is to set the property <kotlin.version></kotlin.version> to your pom project.")
        throw e
    } catch (e: Throwable) {
        logger.log(e, "Your configuration file may have some missing configuration sections. Please ensure that the ${configFile.path} file adheres to the schema described at: https://specmatic.io/documentation/specmatic_json.html")
        throw Exception("Your configuration file may have some missing configuration sections. Please ensure that the ${configFile.path} file adheres to the schema described at: https://specmatic.io/documentation/specmatic_json.html", e)
    }
}