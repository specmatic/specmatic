package io.specmatic.core

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.*
import io.specmatic.conversions.SPECMATIC_BASIC_AUTH_TOKEN
import io.specmatic.conversions.SPECMATIC_OAUTH2_TOKEN
import io.specmatic.core.Configuration.Companion.configFilePath
import io.specmatic.core.SourceProvider.*
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.*
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_1
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_2
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.resolveOrDefault
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.resolveFullyOrNull
import io.specmatic.core.config.v3.resolveMapValuesOrEmpty
import io.specmatic.core.config.v3.wrapOrNull
import io.specmatic.core.config.v3.wrapFullyOrNull
import io.specmatic.core.config.v2.ConsumesDeserializer
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.resolve
import io.specmatic.core.config.v3.wrap
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.*
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_QUERY_PARAMS
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_SCHEMA
import io.specmatic.core.utilities.Flags.Companion.MAX_TEST_COUNT
import io.specmatic.core.utilities.Flags.Companion.MAX_TEST_REQUEST_COMBINATIONS
import io.specmatic.core.utilities.Flags.Companion.ONLY_POSITIVE
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_BASE_URL
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_ESCAPE_SOAP_ACTION
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_PRETTY_PRINT
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_STUB_DELAY
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_PARALLELISM
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_TEST_TIMEOUT
import io.specmatic.core.utilities.Flags.Companion.TEST_LENIENT_MODE
import io.specmatic.core.utilities.Flags.Companion.TEST_STRICT_MODE
import io.specmatic.core.utilities.Flags.Companion.VALIDATE_RESPONSE_VALUE
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue
import io.specmatic.core.utilities.Flags.Companion.getIntValue
import io.specmatic.core.utilities.Flags.Companion.getLongValue
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isSameBaseIgnoringHost
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.collections.filterIsInstance
import kotlin.collections.orEmpty

private const val excludedEndpointsWarning =
    "WARNING: excludedEndpoints is not supported in Specmatic config v2. . Refer to https://specmatic.io/documentation/configuration.html#report-configuration to see how to exclude endpoints."

internal const val TESTS_DIRECTORY_ENV_VAR = "SPECMATIC_TESTS_DIRECTORY"
internal const val TESTS_DIRECTORY_PROPERTY = "specmaticTestsDirectory"
internal const val CUSTOM_IMPLICIT_STUB_BASE_ENV_VAR = "SPECMATIC_CUSTOM_IMPLICIT_STUB_BASE"
internal const val CUSTOM_IMPLICIT_STUB_BASE_PROPERTY = "customImplicitStubBase"
internal const val TEST_ENDPOINTS_API = "endpointsAPI"
internal const val TEST_FILTER_ENV_VAR = "filter"
internal const val TEST_FILTER_PROPERTY = "filter"
private const val TEST_SWAGGER_UI_BASEURL_ENV_VAR = "swaggerUIBaseURL"
private const val TEST_SWAGGER_UI_BASEURL_PROPERTY = "swaggerUIBaseURL"
internal const val TEST_BASE_URL_ENV_VAR = "testBaseURL"
internal const val TEST_BASE_URL_PROPERTY = "testBaseURL"
internal const val TEST_HOST_ENV_VAR = "host"
internal const val TEST_HOST_PROPERTY = "host"
internal const val TEST_PORT_ENV_VAR = "port"
internal const val TEST_PORT_PROPERTY = "port"
private const val TEST_PROTOCOL_ENV_VAR = "protocol"
private const val TEST_PROTOCOL_PROPERTY = "protocol"
internal const val TEST_FILTER_NAME_ENV_VAR = "FILTER_NAME"
internal const val TEST_FILTER_NAME_PROPERTY = "filterName"
internal const val TEST_FILTER_NOT_NAME_ENV_VAR = "FILTER_NOT_NAME"
internal const val TEST_FILTER_NOT_NAME_PROPERTY = "filterNotName"
internal const val TEST_OVERLAY_FILE_PATH_ENV_VAR = "overlayFilePath"
internal const val TEST_OVERLAY_FILE_PATH_PROPERTY = "overlayFilePath"

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
const val SPECMATIC_GITHUB_ISSUES = "https://github.com/specmatic/specmatic/issues"
const val DEFAULT_WORKING_DIRECTORY = ".$APPLICATION_NAME_LOWER_CASE"

const val SPECMATIC_DISABLE_TELEMETRY = "SPECMATIC_DISABLE_TELEMETRY"

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
    val generative: TemplateOrValue<Boolean>? = null,
    val delayInMilliseconds: TemplateOrValue<Long>? = null,
    val dictionary: TemplateOrValue<String>? = null,
    val includeMandatoryAndRequestedKeysInResponse: TemplateOrValue<Boolean>? = null,
    val startTimeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val hotReload: TemplateOrValue<Switch>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null,
    val baseUrl: TemplateOrValue<String>? = null,
    val customImplicitStubBase: TemplateOrValue<String>? = null,
    val filter: TemplateOrValue<String>? = null,
    val gracefulRestartTimeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val https: TemplateOrValue<HttpsConfiguration>? = null,
    val lenientMode: TemplateOrValue<Boolean>? = null
) {
    @get:JsonIgnore
    val resolvedLenientMode: Boolean?
        get() = lenientMode.resolveOrNull()

    @get:JsonIgnore
    val resolvedGenerative: Boolean?
        get() = generative.resolveOrNull()

    @get:JsonIgnore
    val resolvedDelayInMilliseconds: Long?
        get() = delayInMilliseconds.resolveOrNull() ?: getLongValue(SPECMATIC_STUB_DELAY)

    @get:JsonIgnore
    val resolvedDictionary: String?
        get() = dictionary.resolveOrNull() ?: getStringValue(SPECMATIC_STUB_DICTIONARY)

    @get:JsonIgnore
    val resolvedIncludeMandatoryAndRequestedKeysInResponse: Boolean?
        get() = includeMandatoryAndRequestedKeysInResponse.resolveOrNull()

    @get:JsonIgnore
    val resolvedStartTimeoutInMilliseconds: Long?
        get() = startTimeoutInMilliseconds.resolveOrNull()

    @get:JsonIgnore
    val resolvedHotReload: Switch?
        get() = hotReload.resolveOrNull()

    @get:JsonIgnore
    val resolvedStrictMode: Boolean?
        get() = strictMode.resolveOrNull() ?: Flags.getBooleanValueOrNull(Flags.STUB_STRICT_MODE)

    @get:JsonIgnore
    val resolvedFilter: String?
        get() = filter.resolveOrNull()

    @get:JsonIgnore
    val resolvedHttps: HttpsConfiguration?
        get() = https.resolveOrNull()

    @get:JsonIgnore
    val resolvedGracefulRestartTimeoutInMilliseconds: Long?
        get() = gracefulRestartTimeoutInMilliseconds.resolveOrNull()

    @get:JsonIgnore
    val resolvedBaseUrl: String?
        get() = baseUrl.resolveOrNull()

    @get:JsonIgnore
    val resolvedCustomImplicitStubBase: String?
        get() = customImplicitStubBase.resolveOrNull()

    @JsonIgnore
    fun getLenientMode(): Boolean? {
        return resolvedLenientMode
    }

    @JsonIgnore
    fun getGenerative(): Boolean? {
        return resolvedGenerative
    }

    @JsonIgnore
    fun getDelayInMilliseconds(): Long? {
        return resolvedDelayInMilliseconds
    }

    @JsonIgnore
    fun getDictionary(): String? {
        return resolvedDictionary
    }

    @JsonIgnore
    fun getIncludeMandatoryAndRequestedKeysInResponse(): Boolean? {
        return resolvedIncludeMandatoryAndRequestedKeysInResponse
    }

    @JsonIgnore
    fun getStartTimeoutInMilliseconds(): Long? {
        return resolvedStartTimeoutInMilliseconds
    }

    @JsonIgnore
    fun getHotReload(): Switch? {
        return resolvedHotReload
    }

    @JsonIgnore
    fun getStrictMode(): Boolean? {
        return resolvedStrictMode
    }

    @JsonIgnore
    fun getFilter(): String? {
        return resolvedFilter
    }

    @JsonIgnore
    fun getHttps(): HttpsConfiguration? {
        return resolvedHttps
    }

    @JsonIgnore
    fun getGracefulRestartTimeoutInMilliseconds(): Long? {
        return resolvedGracefulRestartTimeoutInMilliseconds
    }

    @JsonIgnore
    fun getBaseUrl(): String? {
        return resolvedBaseUrl
    }

    @JsonIgnore
    fun getCustomImplicitStubBase(): String? {
        return resolvedCustomImplicitStubBase
    }
}

data class VirtualServiceConfiguration(
    val host: TemplateOrValue<String>? = null,
    val port: TemplateOrValue<Int>? = null,
    val specs: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val specsDirPath: TemplateOrValue<String>? = null,
    val logsDirPath: TemplateOrValue<String>? = null,
    val logMode: TemplateOrValue<VSLogMode>? = null,
    val nonPatchableKeys: TemplateOrValue<Set<TemplateOrValue<String>>>? = null
) {

    enum class VSLogMode {
        ALL,
        REQUEST_RESPONSE
    }

    @get:JsonIgnore
    val resolvedHost: String?
        get() = host.resolveOrNull()

    @get:JsonIgnore
    val resolvedPort: Int?
        get() = port.resolveOrNull()

    @get:JsonIgnore
    val resolvedSpecs: List<String>?
        get() = specs.resolveFullyOrNull()

    @get:JsonIgnore
    val resolvedSpecsDirPath: String?
        get() = specsDirPath.resolveOrNull()

    @get:JsonIgnore
    val resolvedLogsDirPath: String?
        get() = logsDirPath.resolveOrNull()

    @get:JsonIgnore
    val resolvedLogMode: VSLogMode?
        get() = logMode.resolveOrNull()

    @get:JsonIgnore
    val resolvedNonPatchableKeys: Set<String>
        get() = nonPatchableKeys.resolveOrNull()?.mapTo(mutableSetOf()) { it.resolve() } ?: emptySet()

    @JsonIgnore
    fun getHost(): String? = resolvedHost

    @JsonIgnore
    fun getPort(): Int? = resolvedPort

    @JsonIgnore
    fun getSpecs(): List<String>? = resolvedSpecs

    @JsonIgnore
    fun getSpecsDirPath(): String? = resolvedSpecsDirPath

    @JsonIgnore
    fun getLogsDirPath(): String? = resolvedLogsDirPath

    @JsonIgnore
    fun getLogMode(): VSLogMode? = resolvedLogMode

    @JsonIgnore
    fun getNonPatchableKeys(): Set<String> = resolvedNonPatchableKeys
}

data class WorkflowIDOperation(
    val extract: TemplateOrValue<String>? = null,
    val use: TemplateOrValue<String>? = null
) {
    @get:JsonIgnore
    val resolvedExtract: String?
        get() = extract.resolveOrNull()

    @get:JsonIgnore
    val resolvedUse: String?
        get() = use.resolveOrNull()

    @JsonIgnore
    fun getExtract(): String? = resolvedExtract

    @JsonIgnore
    fun getUse(): String? = resolvedUse
}

interface WorkflowDetails {
    fun getExtractForAPI(apiDescription: String): String?
    fun getUseForAPI(apiDescription: String): String?

    companion object {
        val default: WorkflowDetails = WorkflowConfiguration()
    }
}

data class WorkflowConfiguration(val ids: TemplateOrValue<Map<String, TemplateOrValue<WorkflowIDOperation>>>? = null) : WorkflowDetails {
    @get:JsonIgnore
    val resolvedIds: Map<String, WorkflowIDOperation>
        get() = ids.resolveMapValuesOrEmpty()

    private fun getOperation(operationId: String): WorkflowIDOperation? {
        return resolvedIds[operationId]
    }

    override fun getExtractForAPI(apiDescription: String): String? {
        return getOperation(apiDescription)?.resolvedExtract
    }

    override fun getUseForAPI(apiDescription: String): String? {
        val operation = getOperation(apiDescription) ?: getOperation("*")
        return operation?.resolvedUse
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
    @param:JsonAlias("default_fields")
    @field:JsonAlias("default_fields")
    val defaultFields: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    @param:JsonAlias("query_param_key")
    @field:JsonAlias("query_param_key")
    val queryParamKey: TemplateOrValue<String>? = null
) : AttributeSelectionPatternDetails {
    @get:JsonIgnore
    val resolvedDefaultFields: List<String>
        get() = defaultFields.resolveFullyOrEmpty()

    @get:JsonIgnore
    val resolvedQueryParamKey: String
        get() = queryParamKey.resolveOrDefault(
            readEnvVarOrProperty(
                ATTRIBUTE_SELECTION_QUERY_PARAM_KEY,
                ATTRIBUTE_SELECTION_QUERY_PARAM_KEY
            ).orEmpty()
        )

    override fun getDefaultFields(): List<String> {
        return resolvedDefaultFields.ifEmpty {
            readEnvVarOrProperty(
            ATTRIBUTE_SELECTION_DEFAULT_FIELDS,
            ATTRIBUTE_SELECTION_DEFAULT_FIELDS
            ).orEmpty().split(",").filter { it.isNotBlank() }
        }
    }

    override fun getQueryParamKey(): String {
        return resolvedQueryParamKey
    }
}

data class SpecmaticConfigV1V2Common(
    private val sources: List<Source> = emptyList(),
    private val auth: Auth? = null,
    private val pipeline: Pipeline? = null,
    private val environments: Map<String, Environment>? = null,
    private val hooks: Map<String, String> = emptyMap(),
    private val proxy: ProxyConfig? = null,
    private val repository: RepositoryInfo? = null,
    private val report: ReportConfigurationDetails? = null,
    private val security: SecurityConfiguration? = null,
    private val test: TestConfiguration? = null,
    private val stub: StubConfiguration? = null,
    private val backwardCompatibility: BackwardCompatibilityConfig? = null,
    private val virtualService: VirtualServiceConfiguration? = null,
    private val examples: List<String>? = null,
    private val workflow: WorkflowConfiguration? = null,
    private val ignoreInlineExamples: Boolean? = null,
    private val ignoreInlineExampleWarnings: Boolean? = null,
    private val schemaExampleDefault: Boolean? = null,
    private val fuzzy: Boolean? = null,
    private val extensibleQueryParams: Boolean? = null,
    private val escapeSoapAction: Boolean? = null,
    private val prettyPrint: Boolean? = null,
    private val additionalExampleParamsFilePath: String? = null,
    private val attributeSelectionPattern: AttributeSelectionPattern? = null,
    private val allPatternsMandatory: Boolean? = null,
    private val defaultPatternValues: Map<String, Any> = emptyMap(),
    private val version: SpecmaticConfigVersion? = null,
    private val disableTelemetry: Boolean? = null,
    private val logging: LoggingConfiguration? = null,
    private val mcp: McpConfiguration? = null,
    private val licensePath: Path? = null,
    private val reportDirPath: Path? = null,
    private val globalSettings: SpecmaticGlobalSettings? = null,
) : SpecmaticConfig {
    companion object {
        fun getEffectiveBranchForSource(
            configuredBranch: String?,
            useCurrentBranchForCentralRepo: Boolean,
        ): String? {
            if (!useCurrentBranchForCentralRepo) {
                return configuredBranch
            }

            return try {
                val git = SystemGit()
                val currentBranch = git.getCurrentBranchForMatchBranch()
                logger.debug("Current branch: $currentBranch")

                logger.log("Central repo branch: '$currentBranch'")
                currentBranch
            } catch (e: Throwable) {
                val fallbackBranchToLog = configuredBranch?.let { "configured branch: $configuredBranch" } ?: "default"

                logger.log("Could not determine current branch for --match-branch flag: ${e.message}")
                logger.log("Falling back to $fallbackBranchToLog")

                configuredBranch
            }
        }

        fun getReport(specmaticConfig: SpecmaticConfigV1V2Common): ReportConfigurationDetails? {
            return specmaticConfig.report
        }

        @JsonIgnore
        fun getSources(specmaticConfig: SpecmaticConfigV1V2Common): List<Source> {
            return specmaticConfig.sources
        }

        @JsonIgnore
        fun getRepository(specmaticConfig: SpecmaticConfigV1V2Common): RepositoryInfo? {
            return specmaticConfig.repository
        }

        @JsonIgnore
        fun getPipeline(specmaticConfig: SpecmaticConfigV1V2Common): Pipeline? {
            return specmaticConfig.pipeline
        }

        @JsonIgnore
        fun getSecurityConfiguration(specmaticConfig: SpecmaticConfigV1V2Common?): SecurityConfiguration? {
            return specmaticConfig?.security
        }

        @JsonIgnore
        fun getWorkflowConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): WorkflowConfiguration? {
            return specmaticConfig.workflow
        }

        @JsonIgnore
        fun getTestConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): TestConfiguration? {
            return specmaticConfig.test
        }

        @JsonIgnore
        fun getVirtualServiceConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): VirtualServiceConfiguration {
            return specmaticConfig.virtualService ?: VirtualServiceConfiguration()
        }

        @JsonIgnore
        fun getAllPatternsMandatory(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? {
            return specmaticConfig.allPatternsMandatory
        }

        @JsonIgnore
        fun getIgnoreInlineExamples(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? {
            return specmaticConfig.ignoreInlineExamples
        }

        @JsonIgnore
        fun getAttributeSelectionPattern(specmaticConfig: SpecmaticConfigV1V2Common): AttributeSelectionPattern {
            return specmaticConfig.attributeSelectionPattern ?: AttributeSelectionPattern()
        }

        @JsonIgnore
        fun getStubConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): StubConfiguration {
            return specmaticConfig.stub ?: StubConfiguration()
        }

        @JsonIgnore
        fun getTestConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): TestConfiguration? {
            return specmaticConfig.test
        }

        @JsonIgnore
        fun getStubConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): StubConfiguration? {
            return specmaticConfig.stub
        }

        @JsonIgnore
        fun getIgnoreInlineExampleWarningsOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.ignoreInlineExampleWarnings

        @JsonIgnore
        fun getEscapeSoapActionOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.escapeSoapAction

        @JsonIgnore
        fun getSchemaExampleDefaultOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.schemaExampleDefault

        @JsonIgnore
        fun getExtensibleQueryParamsOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.extensibleQueryParams

        @JsonIgnore
        fun getFuzzyMatchingEnabledOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.fuzzy

        @JsonIgnore
        fun getPrettyPrintOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.prettyPrint

        @JsonIgnore
        fun isTelemetryDisabledOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.disableTelemetry

        @JsonIgnore
        fun getReportDirPathOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Path? = specmaticConfig.reportDirPath

        @JsonIgnore
        fun getVirtualServiceConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): VirtualServiceConfiguration? {
            return specmaticConfig.virtualService
        }

        @JsonIgnore
        fun getAttributeSelectionConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): AttributeSelectionPattern? {
            return specmaticConfig.attributeSelectionPattern
        }

        fun getEnvironments(specmaticConfig: SpecmaticConfigV1V2Common): Map<String, Environment>? {
            return specmaticConfig.environments
        }

        @JsonIgnore
        fun getHooks(specmaticConfig: SpecmaticConfigV1V2Common): Map<String, String> {
            return specmaticConfig.hooks
        }

        @JsonIgnore
        fun getProxyConfig(specmaticConfig: SpecmaticConfigV1V2Common): ProxyConfig? {
            return specmaticConfig.proxy
        }

        @JsonIgnore
        fun getLogConfigurationOrNull(specmaticConfig: SpecmaticConfigV1V2Common): LoggingConfiguration? {
            return specmaticConfig.logging
        }
    }

    @JsonIgnore
    override fun getLogConfigurationOrDefault(): LoggingConfiguration {
        return this.logging ?: LoggingConfiguration.default()
    }

    @JsonIgnore
    override fun getSpecificationSources(): List<SpecificationSource> {
        val resiliencyTestSuite = test?.resolvedResiliencyTests?.resolvedEnable
        return this.sources.map { source ->
            val specificationSource = SpecificationSource(
                source.resolvedProvider,
                source.resolvedRepository,
                source.resolvedDirectory,
                source.resolvedBranch,
                source.resolvedMatchBranch
            )
            val sourceBaseDir = source.getBaseDirectory()

            val withTestSources = source.resolvedTest.orEmpty().fold(specificationSource) { acc, testExecutionConfig ->
                val testEntries = testExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir, resiliencyTestSuite)
                acc.copy(test = acc.test.plus(testEntries))
            }

            source.resolvedStub.orEmpty().fold(withTestSources) { acc, mockExecutionConfig ->
                val mockEntries = mockExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir)
                acc.copy(mock = acc.mock.plus(mockEntries))
            }
        }
    }

    @JsonIgnore
    override fun getFirstMockSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        val resiliencyTestSuite = test?.resolvedResiliencyTests?.resolvedEnable
        return this.sources.firstNotNullOfOrNull { source ->
            val sourceBaseDir = source.getBaseDirectory()
            source.resolvedStub.orEmpty().firstNotNullOfOrNull { testExecutionConfig ->
                val entries = testExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir, resiliencyTestSuite)
                entries.firstOrNull(predicate)
            }
        }
    }

    @JsonIgnore
    override fun getFirstTestSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        return this.sources.firstNotNullOfOrNull { source ->
            val sourceBaseDir = source.getBaseDirectory()
            source.resolvedTest.orEmpty().firstNotNullOfOrNull { testExecutionConfig ->
                val entries = testExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir)
                entries.firstOrNull(predicate)
            }
        }
    }

    @JsonIgnore
    override fun isTelemetryDisabled(): Boolean {
        val disableTelemetryFromEnvVarOrSystemProp = readEnvVarOrProperty(
            SPECMATIC_DISABLE_TELEMETRY, SPECMATIC_DISABLE_TELEMETRY
        )

        return disableTelemetryFromEnvVarOrSystemProp?.toBoolean()
            ?: (disableTelemetry == true)
    }

    @JsonIgnore
    override fun testConfigFor(specPath: String, specType: String): SpecmaticSpecConfig? {
        return sources.flatMap { it.resolvedTest.orEmpty() }.configWith(specPath, specType)
    }

    @JsonIgnore
    override fun testConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig? {
        val resolvedTestConfig = this.sources.flatMap { it.getCanonicalTestConfigs() }
        return resolvedTestConfig.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull { config ->
            if (config.resolvedSpecType != specType.value) return@firstOrNull false
            config.resolvedSpecs.any { specPath -> File(specPath).sameAs(file) }
        }?.resolvedConfig.orEmpty().let(::SpecmaticSpecConfig)
    }

    @JsonIgnore
    override fun stubConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig? {
        val resolvedTestConfig = this.sources.flatMap { it.getCanonicalStubConfigs() }
        return resolvedTestConfig.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull { config ->
            if (config.resolvedSpecType != specType.value) return@firstOrNull false
            config.resolvedSpecs.any { specPath -> File(specPath).sameAs(file) }
        }?.resolvedConfig.orEmpty().let(::SpecmaticSpecConfig)
    }

    @JsonIgnore
    override fun stubConfigFor(specPath: String, specType: String): SpecmaticSpecConfig? {
        return sources.flatMap { it.resolvedStub.orEmpty() }.configWith(specPath, specType)
    }

    @JsonIgnore
    private fun List<SpecExecutionConfig>.configWith(specPath: String, specType: String): SpecmaticSpecConfig? {
        return this.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull {
            it.contains(specPath, specType)
        }?.resolvedConfig.orEmpty().let(::SpecmaticSpecConfig)
    }

    @JsonIgnore
    override fun getCtrfSpecConfig(specFile: File, testType: String, protocol: String, specType: String): CtrfSpecConfig {
        val source = when (testType) {
            CONTRACT_TEST_TEST_TYPE -> testSourceFromConfig(specFile)
            else -> stubSourceFromConfig(specFile)
        } ?: Source()

        val specPathFromConfig = when(testType) {
            CONTRACT_TEST_TEST_TYPE -> testSpecPathFromConfigFor(specFile)
            else -> stubSpecPathFromConfigFor(specFile)
        }

        return CtrfSpecConfig(
            protocol = protocol,
            specType = specType,
            specification = specPathFromConfig.orEmpty(),
            sourceProvider = source.resolvedProvider.name,
            repository = source.resolvedRepository.orEmpty(),
            branch = source.resolvedBranch ?: "main",
        )
    }

    @JsonIgnore
    override fun getHotReload(): Switch? {
        return getStubConfiguration(this).getHotReload()
    }

    @JsonIgnore
    override fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfigV1V2Common {
        if (latestVersion == VERSION_1)
            return this

        logger.log("\n$excludedEndpointsWarning\n")

        return this.copy(
            report = report?.clearPresenceOfExcludedEndpoints()
        )
    }

    @JsonIgnore
    override fun getReport(): ReportConfiguration? {
        return report
    }

    @JsonIgnore
    override fun getWorkflowDetails(): WorkflowDetails? {
        return workflow
    }

    @JsonIgnore
    override fun getAttributeSelectionPattern(): AttributeSelectionPatternDetails {
        return getAttributeSelectionPattern(this)
    }

    @JsonIgnore
    override fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings {
        return this.globalSettings ?: SpecmaticGlobalSettings()
    }

    @JsonIgnore
    override fun stubBaseUrls(defaultBaseUrl: String): List<String> =
        sources
            .flatMap { source ->
                source.resolvedStub.orEmpty().map { consumes ->
                    baseUrlFrom(consumes, defaultBaseUrl)
                }
            }.distinct()

    @JsonIgnore
    override fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
        return sources.flatMap { source ->
            source.resolvedStub.orEmpty().flatMap { consumes ->
                when (consumes) {
                    is SpecExecutionConfig.StringValue -> listOf(consumes.resolvedValue to defaultBaseUrl)
                    is SpecExecutionConfig.ObjectValue -> consumes.resolvedSpecs.map { it to consumes.toBaseUrl(defaultBaseUrl) }
                    is SpecExecutionConfig.ConfigValue -> consumes.resolvedSpecs.map { it to defaultBaseUrl }
                }
            }
        }
    }

    @JsonIgnore
    override fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String {
        val parsedUrl = URI(url)
        return stubBaseUrls(defaultBaseUrl).map(::URI).firstOrNull { stubBaseUrl ->
            isSameBaseIgnoringHost(parsedUrl, stubBaseUrl)
        }?.path.orEmpty()
    }

    @JsonIgnore
    override fun getStubStartTimeoutInMilliseconds(): Long {
        return getStubConfiguration(this).getStartTimeoutInMilliseconds() ?: 20_000L
    }

    @JsonIgnore
    private fun baseUrlFrom(
        consumes: SpecExecutionConfig,
        defaultBaseUrl: String
    ): String = if (consumes is SpecExecutionConfig.ObjectValue) consumes.toBaseUrl(defaultBaseUrl) else defaultBaseUrl

    override fun logDependencyProjects(azure: AzureAPI) {
        logger.log("Dependency projects")
        logger.log("-------------------")

        sources.forEach { source ->
            logger.log("In central repo ${source.resolvedRepository}")

            source.specsUsedAsTest().forEach { relativeContractPath ->
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

    override fun getProxyIgnoreHeaders(): List<String> {
        return emptyList()
    }

    override fun isProxyRecordEnabled(): Boolean? {
        return true
    }

    @JsonIgnore
    override fun loadSources(useCurrentBranchForCentralRepo: Boolean): List<ContractSource> {
        return sources.map { source ->
            val defaultBaseUrl = getStubConfiguration(this).getBaseUrl() ?: getStringValue(SPECMATIC_BASE_URL) ?: Configuration.DEFAULT_BASE_URL
            val stubExamplesMap = source.specToStubExamplesMap()
            val stubPaths = source.specToStubBaseUrlMap(defaultBaseUrl).entries.map { ContractSourceEntry(it.key, it.value, exampleDirPaths = stubExamplesMap[it.key]) }

            val testBaseUrlMap = source.specToTestBaseUrlMap(defaultBaseUrl)
            val testGenerativeMap = source.specToTestGenerativeMap()
            val testExamplesMap = source.specToTestExamplesMap()
            val testPaths = testBaseUrlMap.entries.map { ContractSourceEntry(it.key, it.value, testGenerativeMap[it.key], exampleDirPaths = testExamplesMap[it.key]) }

            val sourceMatchBranch = source.resolvedMatchBranch ?: false
            val effectiveUseCurrentBranch = useCurrentBranchForCentralRepo || sourceMatchBranch
            val configuredBranch = source.resolvedBranch
            val effectiveBranch = getEffectiveBranchForSource(configuredBranch, effectiveUseCurrentBranch)
            val repository = source.resolvedRepository


            when (source.resolvedProvider) {
                git -> when (repository) {
                    null -> GitMonoRepo(testPaths, stubPaths, source.resolvedProvider.toString())
                    else -> GitRepo(
                        repository,
                        effectiveBranch,
                        testPaths,
                        stubPaths,
                        source.resolvedProvider.toString(),
                        effectiveUseCurrentBranch,
                        specmaticConfig = this
                    )
                }

                filesystem -> LocalFileSystemSource(source.resolvedDirectory ?: ".", testPaths, stubPaths)

                web -> source.resolvedWebBaseUrl?.let { ResolvedWebSource(it, testPaths, stubPaths) } ?: WebSource(testPaths, stubPaths)
            }
        }
    }

    override fun getStubLenientMode(file: File): Boolean? {
        return null
    }

    @JsonIgnore
    override fun attributeSelectionQueryParamKey(): String {
        return getAttributeSelectionPattern().getQueryParamKey()
    }

    @JsonIgnore
    override fun isExtensibleSchemaEnabled(): Boolean {
        return test?.resolvedAllowExtensibleSchema ?: getBooleanValue(EXTENSIBLE_SCHEMA)
    }

    @JsonIgnore
    override fun isResiliencyTestingEnabled(): Boolean {
        return (getResiliencyTestsEnabled() != ResiliencyTestSuite.none)
    }

    @JsonIgnore
    override fun isOnlyPositiveTestingEnabled(): Boolean {
        return (getResiliencyTestsEnabled() == ResiliencyTestSuite.positiveOnly)
    }

    @JsonIgnore
    override fun isResponseValueValidationEnabled(): Boolean {
        return test?.resolvedValidateResponseValues ?: getBooleanValue(VALIDATE_RESPONSE_VALUE)
    }

    @JsonIgnore
    override fun parsedDefaultPatternValues(): Map<String, Value> {
        return parsedJSONObject(ObjectMapper().writeValueAsString(defaultPatternValues)).jsonObject
    }

    @JsonIgnore
    override fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        return (test?.resolvedResiliencyTests ?: ResiliencyTestsConfig.fromSystemProperties()).resolvedEnable ?: ResiliencyTestSuite.none
    }

    @JsonIgnore
    override fun getTestTimeoutInMilliseconds(): Long? {
        return test?.resolvedTimeoutInMilliseconds ?: getLongValue(SPECMATIC_TEST_TIMEOUT)
    }

    @JsonIgnore
    override fun getMaxTestRequestCombinations(): Int? {
        val configValue = if (getVersion() == VERSION_2) test?.resolvedMaxTestRequestCombinations else null
        return configValue ?: getIntValue(MAX_TEST_REQUEST_COMBINATIONS)
    }

    @JsonIgnore
    override fun getTestStrictMode(): Boolean? {
        return test?.resolvedStrictMode ?: getStringValue(TEST_STRICT_MODE)?.toBoolean()
    }

    @JsonIgnore
    override fun getTestLenientMode(): Boolean? {
        return test?.resolvedLenientMode ?: getStringValue(TEST_LENIENT_MODE)?.toBoolean()
    }

    @JsonIgnore
    override fun getTestParallelism(): String? {
        return test?.resolvedParallelism ?: getStringValue(SPECMATIC_TEST_PARALLELISM)
    }

    @JsonIgnore
    override fun getTestsDirectory(): String? {
        return test?.resolvedTestsDirectory
            ?: readEnvVarOrProperty(TESTS_DIRECTORY_ENV_VAR, TESTS_DIRECTORY_PROPERTY)
    }

    @JsonIgnore
    override fun getMaxTestCount(): Int? {
        return test?.resolvedMaxTestCount ?: getIntValue(MAX_TEST_COUNT)
    }

    @JsonIgnore
    override fun getTestFilter(): String? {
        return getTestConfiguration(this)?.resolvedFilter
            ?: readEnvVarOrProperty(TEST_FILTER_ENV_VAR, TEST_FILTER_PROPERTY)
    }

    @JsonIgnore
    override fun getTestFilterName(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.resolvedFilterName else null
        return configValue ?: readEnvVarOrProperty(TEST_FILTER_NAME_ENV_VAR, TEST_FILTER_NAME_PROPERTY)
    }

    @JsonIgnore
    override fun getTestFilterNotName(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.resolvedFilterNotName else null
        return configValue ?: readEnvVarOrProperty(TEST_FILTER_NOT_NAME_ENV_VAR, TEST_FILTER_NOT_NAME_PROPERTY)
    }

    @JsonIgnore
    override fun getTestOverlayFilePath(specFile: File, specType: SpecType): String? {
        val configValue = if (getVersion() == VERSION_2) test?.resolvedOverlayFilePath else null
        return configValue ?: readEnvVarOrProperty(TEST_OVERLAY_FILE_PATH_ENV_VAR, TEST_OVERLAY_FILE_PATH_PROPERTY)
    }

    @JsonIgnore
    override fun getStubOverlayFilePath(specFile: File, specType: SpecType): String? {
        return getTestOverlayFilePath(specFile, specType)
    }

    @JsonIgnore
    override fun getTestBaseUrl(specType: SpecType): String? {
        val baseUrl = getExplicitTestBaseUrl()
        if (baseUrl != null) return baseUrl

        val rawHost = readEnvVarOrProperty(TEST_HOST_ENV_VAR, TEST_HOST_PROPERTY)
        val port = readEnvVarOrProperty(TEST_PORT_ENV_VAR, TEST_PORT_PROPERTY)
        if (rawHost.isNullOrBlank() || port.isNullOrBlank()) return null

        val host = if (rawHost.startsWith("http")) {
            URI(rawHost).host ?: return null
        } else {
            rawHost
        }

        val protocol = readEnvVarOrProperty(TEST_PROTOCOL_ENV_VAR, TEST_PROTOCOL_PROPERTY) ?: "http"
        val constructedBaseUrl = "$protocol://$host:$port"
        return if (validateTestOrStubUri(constructedBaseUrl) == URIValidationResult.Success) {
            constructedBaseUrl
        } else {
            null
        }
    }

    @JsonIgnore
    override fun getCoverageReportBaseUrl(specType: SpecType): String? {
        val baseUrl = getExplicitTestBaseUrl()
        if (baseUrl != null) return baseUrl

        val host = readEnvVarOrProperty(TEST_HOST_ENV_VAR, TEST_HOST_PROPERTY).orEmpty()
        val port = readEnvVarOrProperty(TEST_PORT_ENV_VAR, TEST_PORT_PROPERTY).orEmpty()
        return if (host.isNotBlank() && port.isNotBlank()) "$host:$port" else null
    }

    @JsonIgnore
    private fun getExplicitTestBaseUrl(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.resolvedBaseUrl else null
        return configValue ?: readEnvVarOrProperty(TEST_BASE_URL_ENV_VAR, TEST_BASE_URL_PROPERTY)
    }

    @JsonIgnore
    override fun getTestSwaggerUrl(): String? {
        return getTestConfiguration(this)?.resolvedSwaggerUrl
    }

    @JsonIgnore
    override fun getTestSwaggerUIBaseUrl(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.resolvedSwaggerUIBaseURL else null
        return configValue ?: readEnvVarOrProperty(TEST_SWAGGER_UI_BASEURL_ENV_VAR, TEST_SWAGGER_UI_BASEURL_PROPERTY)
    }

    @JsonIgnore
    override fun getTestJunitReportDir(): String? {
        return if (getVersion() == VERSION_2) test?.resolvedJunitReportDir else null
    }

    @JsonIgnore
    override fun getActuatorUrl(): String? {
        return getTestConfiguration(this)?.resolvedActuatorUrl ?: getStringValue(TEST_ENDPOINTS_API)
    }

    @JsonIgnore
    override fun enableResiliencyTests(onlyPositive: Boolean): SpecmaticConfigV1V2Common {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                resiliencyTests = (testConfig.resolvedResiliencyTests ?: ResiliencyTestsConfig.fromSystemProperties()).copy(
                    enable = if (onlyPositive) ResiliencyTestSuite.positiveOnly.wrapOrNull() else ResiliencyTestSuite.all.wrapOrNull()
                ).wrapOrNull()
            )
        )
    }

    override fun disableResiliencyTests(): SpecmaticConfig {
        val testConfig = test ?: TestConfiguration()
        val resiliencyTestsConfig = ResiliencyTestsConfig(enable = ResiliencyTestSuite.none.wrapOrNull())
        return this.copy(test = testConfig.copy(resiliencyTests = resiliencyTestsConfig.wrapOrNull()))
    }

    @JsonIgnore
    override fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        return getStubConfiguration(this).getIncludeMandatoryAndRequestedKeysInResponse() ?: true
    }

    @JsonIgnore
    override fun getStubGenerative(specFile: File?): Boolean {
        return getStubConfiguration(this).getGenerative() ?: false
    }

    @JsonIgnore
    override fun getStubDelayInMilliseconds(specFile: File?): Long? {
        return getStubConfiguration(this).getDelayInMilliseconds()
    }

    @JsonIgnore
    override fun getStubDictionary(specFile: File?): String? {
        return getStubConfiguration(this).getDictionary()
    }

    @JsonIgnore
    override fun getTestDictionary(): String? {
        return getStubConfiguration(this).getDictionary()
    }

    override fun getDictionary(): String? {
        return getStubConfiguration(this).getDictionary()
    }

    @JsonIgnore
    override fun getStubStrictMode(specFile: File?): Boolean? {
        return getStubConfiguration(this).getStrictMode()
    }

    @JsonIgnore
    override fun getStubFilter(specFile: File): String? {
        return getStubConfiguration(this).getFilter()
    }

    @JsonIgnore
    override fun getStubHttpsConfiguration(): CertRegistry {
        val registry = CertRegistry.empty()
        val httpsConfiguration = getStubConfiguration(this).getHttps() ?: return registry
        return registry.plusWildCard(httpsConfiguration)
    }

    @JsonIgnore
    override fun getTestHttpsConfiguration(): CertRegistry {
        val registry = CertRegistry.empty()
        val httpsConfiguration = test?.resolvedHttps ?: return registry
        return registry.plusWildCard(httpsConfiguration)
    }

    @JsonIgnore
    override fun getStubGracefulRestartTimeoutInMilliseconds(): Long? {
        return getStubConfiguration(this).getGracefulRestartTimeoutInMilliseconds()
    }

    @JsonIgnore
    override fun getDefaultBaseUrl(): String {
        return getStubConfiguration(this).getBaseUrl()
            ?: getStringValue(SPECMATIC_BASE_URL)
            ?: Configuration.DEFAULT_BASE_URL
    }

    @JsonIgnore
    override fun getCustomImplicitStubBase(): String? {
        return getStubConfiguration(this).getCustomImplicitStubBase()
            ?: readEnvVarOrProperty(CUSTOM_IMPLICIT_STUB_BASE_ENV_VAR, CUSTOM_IMPLICIT_STUB_BASE_PROPERTY)
    }

    @JsonIgnore
    override fun getIgnoreInlineExamples(): Boolean {
        return ignoreInlineExamples ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES)
    }

    @JsonIgnore
    override fun getIgnoreInlineExampleWarnings(): Boolean {
        return ignoreInlineExampleWarnings ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS)
    }

    @JsonIgnore
    override fun getAllPatternsMandatory(): Boolean {
        return allPatternsMandatory ?: getBooleanValue(Flags.ALL_PATTERNS_MANDATORY)
    }

    @JsonIgnore
    override fun getSchemaExampleDefault(): Boolean {
        val configValue = if (getVersion() == VERSION_2) schemaExampleDefault else null
        return configValue ?: getBooleanValue(Flags.SCHEMA_EXAMPLE_DEFAULT)
    }

    @JsonIgnore
    override fun getFuzzyMatchingEnabled(): Boolean {
        val configValue = if (getVersion() == VERSION_2) fuzzy else null
        return configValue ?: getBooleanValue(Flags.SPECMATIC_FUZZY)
    }

    @JsonIgnore
    override fun getExtensibleQueryParams(): Boolean {
        val configValue = if (getVersion() == VERSION_2) extensibleQueryParams else null
        return configValue ?: getBooleanValue(EXTENSIBLE_QUERY_PARAMS)
    }

    @JsonIgnore
    override fun getEscapeSoapAction(): Boolean {
        val configValue = if (getVersion() == VERSION_2) escapeSoapAction else null
        return configValue ?: getBooleanValue(SPECMATIC_ESCAPE_SOAP_ACTION)
    }

    @JsonIgnore
    override fun getPrettyPrint(): Boolean {
        val configValue = if (getVersion() == VERSION_2) prettyPrint else null
        return configValue ?: getBooleanValue(SPECMATIC_PRETTY_PRINT, true)
    }

    @JsonIgnore
    override fun getAdditionalExampleParamsFilePath(): String? {
        return additionalExampleParamsFilePath ?: getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)
    }

    @JsonIgnore
    override fun getHooks(): Map<String, String> {
        return hooks
    }

    override fun getStubHooks(): List<FileAssociation<Map<String, String>>> {
        return listOf(FileAssociation.Global(this.hooks))
    }

    @JsonIgnore
    override fun getProxyConfig(): ProxyConfig? {
        return proxy
    }

    @JsonIgnore
    override fun getDefaultPatternValues(): Map<String, Any> {
        return defaultPatternValues
    }

    override fun getVersion(): SpecmaticConfigVersion {
        return this.version ?: VERSION_1
    }

    @JsonIgnore
    override fun getMatchBranchEnabled(): Boolean {
        return sources.any { it.resolvedMatchBranch == true } || getBooleanValue(Flags.MATCH_BRANCH)
    }

    @JsonIgnore
    override fun getAuth(repositoryUrl: String): Auth? {
        return auth
    }

    @JsonIgnore
    fun getAuth(): Auth? = auth

    @JsonIgnore
    override fun getAuthBearerFile(repositoryUrl: String): String? {
        return auth?.resolvedBearerFile
    }

    @JsonIgnore
    override fun getAuthBearerEnvironmentVariable(repositoryUrl: String): String? {
        return auth?.resolvedBearerEnvironmentVariable
    }

    @JsonIgnore
    override fun getAuthPersonalAccessToken(repositoryUrl: String): String? {
        val tokenFromConfig = auth?.resolvedPersonalAccessToken?.takeIf { it.isNotBlank() }
        if (tokenFromConfig != null) return tokenFromConfig

        val tokenFromEnv = System.getenv("PERSONAL_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
        if (tokenFromEnv != null) {
            logger.log("Using personal access token from environment variable")
            return tokenFromEnv
        }

        val tokenFromProperty = System.getProperty("personalAccessToken")?.takeIf { it.isNotBlank() }
        if (tokenFromProperty != null) {
            logger.log("Using personal access token from system property")
            return tokenFromProperty
        }

        return getPersonalAccessTokenFromHomeDirectoryConfig()
    }

    private fun getPersonalAccessTokenFromHomeDirectoryConfig(): String? {
        val homeDir = File(System.getProperty("user.home"))
        val configFile = homeDir.resolve("specmatic-azure.json")

        if (!configFile.exists()) return null

        val config = parsedJSONObject(configFile.readText())
        val tokenKeys = listOf("azure-access-token", "personal-access-token")

        val tokenFromHomeConfig = tokenKeys.firstNotNullOfOrNull { tokenKey ->
            when {
                config.jsonObject.containsKey(tokenKey) ->
                    config.getString(tokenKey).takeIf { it.isNotBlank() }
                else -> null
            }
        }

        return tokenFromHomeConfig?.also {
            logger.log("Using personal access token from home directory config")
        }
    }

    @JsonIgnore
    override fun getTestExampleDirs(specFile: File): List<String> {
        return examples ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    override fun getStubExampleDirs(specFile: File): List<String> {
        return examples ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    override fun getExamples(): List<String> {
        return examples ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    override fun getRepositoryProvider(): String? {
        return repository?.getProvider()
    }

    @JsonIgnore
    override fun getRepositoryCollectionName(): String? {
        return repository?.getCollectionName()
    }

    @JsonIgnore
    override fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig? {
        return this.backwardCompatibility
    }

    @JsonIgnore
    override fun getMcpConfiguration(): McpConfiguration? {
        return this.mcp
    }

    @JsonIgnore
    override fun getPipelineProvider(): PipelineProvider? {
        return pipeline?.getProvider()
    }

    @JsonIgnore
    override fun getPipelineDefinitionId(): Int? {
        return pipeline?.getDefinitionId()
    }

    @JsonIgnore
    override fun getPipelineOrganization(): String? {
        return pipeline?.getOrganization()
    }

    @JsonIgnore
    override fun getPipelineProject(): String? {
        return pipeline?.getProject()
    }

    @JsonIgnore
    override fun getOpenAPISecurityConfigurationScheme(specFile: File, scheme: String): SecuritySchemeConfiguration? {
        return security?.getOpenAPISecurityScheme(scheme)
    }

    @JsonIgnore
    override fun getBasicAuthSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? BasicAuthSecuritySchemeConfiguration)?.token?.resolveOrNull()
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_BASIC_AUTH_TOKEN)
    }

    @JsonIgnore
    override fun getBearerSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? SecuritySchemeWithOAuthToken)?.token?.resolveOrNull()
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_OAUTH2_TOKEN)
    }

    @JsonIgnore
    override fun getApiKeySecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? APIKeySecuritySchemeConfiguration)?.value?.resolveOrNull()
        return resolveSecurityToken(tokenFromConfig, schemeName)
    }

    private fun resolveSecurityToken(
        tokenFromConfig: String?,
        schemeName: String,
        defaultEnvVar: String? = null
    ): String? {
        val configuredToken = tokenFromConfig?.takeIf { it.isNotBlank() }
        return configuredToken
            ?: getStringValue(schemeName)
            ?: defaultEnvVar?.let { getStringValue(it) }
    }

    @JsonIgnore
    override fun getVirtualServiceHost(): String? {
        return getVirtualServiceConfiguration(this).getHost()
    }

    @JsonIgnore
    override fun getVirtualServicePort(): Int? {
        return getVirtualServiceConfiguration(this).getPort()
    }

    @JsonIgnore
    override fun getVirtualServiceSpecs(): List<String>? {
        return getVirtualServiceConfiguration(this).getSpecs()
    }

    @JsonIgnore
    override fun getVirtualServiceSpecsDirPath(): String? {
        return getVirtualServiceConfiguration(this).getSpecsDirPath()
    }

    @JsonIgnore
    override fun getVirtualServiceLogsDirPath(): String? {
        return getVirtualServiceConfiguration(this).getLogsDirPath()
    }

    @JsonIgnore
    override fun getVirtualServiceLogMode(): VirtualServiceConfiguration.VSLogMode? {
        return getVirtualServiceConfiguration(this).getLogMode()
    }

    @JsonIgnore
    override fun getVirtualServiceNonPatchableKeys(): Set<String> {
        return getVirtualServiceConfiguration(this).getNonPatchableKeys()
    }

    @JsonIgnore
    override fun stubContracts(relativeTo: File): List<String> {
        return sources.flatMap { source ->
            source.resolvedStub.orEmpty().flatMap { stub ->
                stub.specs()
            }.map { spec ->
                if (source.resolvedProvider == web) spec
                else spec.canonicalPath(relativeTo)
            }
        }
    }

    override fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfigV1V2Common {
        val reportConfigurationDetails = reportConfiguration as? ReportConfigurationDetails ?: return this
        return this.copy(report = reportConfigurationDetails)
    }

    override fun getEnvironment(envName: String): JSONObjectValue {
        val envConfigFromFile = environments?.get(envName) ?: return JSONObjectValue()

        try {
            return parsedJSONObject(content = ObjectMapper().writeValueAsString(envConfigFromFile))
        } catch(e: Throwable) {
            throw ContractException("Error loading Specmatic configuration: ${e.message}")
        }
    }

    override fun enableResiliencyTests(): SpecmaticConfigV1V2Common {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                resiliencyTests = (testConfig.resolvedResiliencyTests ?: ResiliencyTestsConfig()).copy(
                    enable = ResiliencyTestSuite.all.wrapOrNull(),
                ).wrapOrNull(),
            ),
        )
    }

    override fun withTestModes(strictMode: Boolean?, lenientMode: Boolean?): SpecmaticConfigV1V2Common {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                strictMode = (strictMode ?: testConfig.resolvedStrictMode).wrapOrNull(),
                lenientMode = (lenientMode ?: testConfig.resolvedLenientMode).wrapOrNull(),
            ),
        )
    }

    override fun withTestBaseURL(testBaseURL: String): SpecmaticConfig {
        val testConfig = test ?: TestConfiguration()
        return this.copy(test = testConfig.copy(baseUrl = testBaseURL.wrap()))
    }

    override fun withTestFilter(filter: String?): SpecmaticConfigV1V2Common {
        if (filter == null) return this
        val testConfig = this.test ?: TestConfiguration()
        return this.copy(test = testConfig.copy(filter = filter.wrap()))
    }

    override fun withTestTimeout(timeoutInMilliseconds: Long?): SpecmaticConfigV1V2Common {
        if (timeoutInMilliseconds == null) return this
        val testConfig = this.test ?: TestConfiguration()
        return this.copy(test = testConfig.copy(timeoutInMilliseconds = timeoutInMilliseconds.wrap()))
    }

    override fun withStubModes(strictMode: Boolean?): SpecmaticConfigV1V2Common {
        if (strictMode == null) return this
        val stubConfig = this.stub ?: StubConfiguration()
        return this.copy(stub = stubConfig.copy(strictMode = strictMode.wrap()))
    }

    override fun withStubFilter(filter: String?): SpecmaticConfigV1V2Common {
        if (filter == null) return this
        val stubConfig = this.stub ?: StubConfiguration()
        return this.copy(stub = stubConfig.copy(filter = filter.wrap()))
    }

    override fun withGlobalMockDelay(delayInMilliseconds: Long): SpecmaticConfigV1V2Common {
        val stubConfig = this.stub ?: StubConfiguration()
        return this.copy(stub = stubConfig.copy(delayInMilliseconds = delayInMilliseconds.wrap()))
    }

    override fun withMatchBranch(matchBranch: Boolean): SpecmaticConfig {
        val transformedSources = this.sources.map { source -> source.copy(matchBranch = matchBranch.wrap()) }
        return this.copy(sources = transformedSources)
    }

    @JsonIgnore
    override fun testSpecPathFromConfigFor(specFile: File): String? {
        val source = testSourceFromConfig(specFile) ?: return null
        return source.firstTestSpecMatching(specFile)
    }

    @JsonIgnore
    override fun stubSpecPathFromConfigFor(specFile: File): String? {
        val source = stubSourceFromConfig(specFile) ?: return null
        return source.firstStubSpecMatching(specFile)
    }

    @JsonIgnore
    private fun testSourceFromConfig(specFile: File): Source? {
        return sources.firstOrNull { source ->
            source.firstTestSpecMatching(specFile) != null
        }
    }

    @JsonIgnore
    private fun stubSourceFromConfig(specFile: File): Source? {
        return sources.firstOrNull { source ->
            source.firstStubSpecMatching(specFile) != null
        }
    }

    @JsonIgnore
    private fun String.canonicalPath(relativeTo: File): String {
        return relativeTo.parentFile?.resolve(this)?.canonicalPath ?: File(this).canonicalPath
    }

    @JsonIgnore
    override fun getLicensePath(): Path? {
        return licensePath
    }

    @JsonIgnore
    override fun getReportDirPath(suffix: String?): Path {
        val reportDirPath = reportDirPath ?: defaultReportDirPath
        return if (suffix == null) reportDirPath
        else reportDirPath.resolve(suffix)
    }

    override fun plusExamples(exampleDirectories: List<String>): SpecmaticConfig {
        return copy(examples = examples.orEmpty().plus(exampleDirectories))
    }

    @JsonIgnore
    override fun getSecurityConfiguration(specFile: File): SecurityConfiguration? {
        return security
    }

    private fun File.sameAs(other: File): Boolean {
        return try {
            this.toPath().toRealPath() == other.toPath().toRealPath()
        } catch (_: Exception) {
            this.canonicalFile == other.canonicalFile
        }
    }

    private fun Source.firstTestSpecMatching(specFile: File): String? {
        return resolvedTest.orEmpty().asSequence().flatMap { it.specs().asSequence() }.firstOrNull { specPath ->
            resolveSpecFile(specPath).sameAs(specFile)
        }
    }

    private fun Source.firstStubSpecMatching(specFile: File): String? {
        return resolvedStub.orEmpty().asSequence().flatMap { it.specs().asSequence() }.firstOrNull { specPath ->
            resolveSpecFile(specPath).sameAs(specFile)
        }
    }
}

data class TestConfiguration(
    val resiliencyTests: TemplateOrValue<ResiliencyTestsConfig>? = null,
    val validateResponseValues: TemplateOrValue<Boolean>? = null,
    val allowExtensibleSchema: TemplateOrValue<Boolean>? = null,
    val timeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val strictMode: TemplateOrValue<Boolean>? = null,
    val lenientMode: TemplateOrValue<Boolean>? = null,
    val parallelism: TemplateOrValue<String>? = null,
    val maxTestRequestCombinations: TemplateOrValue<Int>? = null,
    val maxTestCount: TemplateOrValue<Int>? = null,
    val testsDirectory: TemplateOrValue<String>? = null,
    val swaggerUrl: TemplateOrValue<String>? = null,
    val swaggerUIBaseURL: TemplateOrValue<String>? = null,
    val actuatorUrl: TemplateOrValue<String>? = null,
    val filter: TemplateOrValue<String>? = null,
    val baseUrl: TemplateOrValue<String>? = null,
    val filterName: TemplateOrValue<String>? = null,
    val filterNotName: TemplateOrValue<String>? = null,
    val overlayFilePath: TemplateOrValue<String>? = null,
    val junitReportDir: TemplateOrValue<String>? = null,
    val https: TemplateOrValue<HttpsConfiguration>? = null,
) 
{
    @get:JsonIgnore
    val resolvedResiliencyTests: ResiliencyTestsConfig?
        get() = resiliencyTests.resolveOrNull()

    @get:JsonIgnore
    val resolvedValidateResponseValues: Boolean?
        get() = validateResponseValues.resolveOrNull()

    @get:JsonIgnore
    val resolvedAllowExtensibleSchema: Boolean?
        get() = allowExtensibleSchema.resolveOrNull()

    @get:JsonIgnore
    val resolvedTimeoutInMilliseconds: Long?
        get() = timeoutInMilliseconds.resolveOrNull()

    @get:JsonIgnore
    val resolvedStrictMode: Boolean?
        get() = strictMode.resolveOrNull()

    @get:JsonIgnore
    val resolvedLenientMode: Boolean?
        get() = lenientMode.resolveOrNull()

    @get:JsonIgnore
    val resolvedParallelism: String?
        get() = parallelism.resolveOrNull()

    @get:JsonIgnore
    val resolvedMaxTestRequestCombinations: Int?
        get() = maxTestRequestCombinations.resolveOrNull()

    @get:JsonIgnore
    val resolvedMaxTestCount: Int?
        get() = maxTestCount.resolveOrNull()

    @get:JsonIgnore
    val resolvedTestsDirectory: String?
        get() = testsDirectory.resolveOrNull()

    @get:JsonIgnore
    val resolvedSwaggerUrl: String?
        get() = swaggerUrl.resolveOrNull()

    @get:JsonIgnore
    val resolvedSwaggerUIBaseURL: String?
        get() = swaggerUIBaseURL.resolveOrNull()

    @get:JsonIgnore
    val resolvedActuatorUrl: String?
        get() = actuatorUrl.resolveOrNull()

    @get:JsonIgnore
    val resolvedFilter: String?
        get() = filter.resolveOrNull()

    @get:JsonIgnore
    val resolvedBaseUrl: String?
        get() = baseUrl.resolveOrNull()

    @get:JsonIgnore
    val resolvedFilterName: String?
        get() = filterName.resolveOrNull()

    @get:JsonIgnore
    val resolvedFilterNotName: String?
        get() = filterNotName.resolveOrNull()

    @get:JsonIgnore
    val resolvedOverlayFilePath: String?
        get() = overlayFilePath.resolveOrNull()

    @get:JsonIgnore
    val resolvedJunitReportDir: String?
        get() = junitReportDir.resolveOrNull()

    @get:JsonIgnore
    val resolvedHttps: HttpsConfiguration?
        get() = https.resolveOrNull()
}

enum class ResiliencyTestSuite {
    all, positiveOnly, none
}

data class ResiliencyTestsConfig(
    val enable: TemplateOrValue<ResiliencyTestSuite>? = null
) {
    constructor(isResiliencyTestFlagEnabled: Boolean, isOnlyPositiveFlagEnabled: Boolean) : this(
        enable = getEnableFrom(isResiliencyTestFlagEnabled, isOnlyPositiveFlagEnabled).wrapOrNull()
    )

    @get:JsonIgnore
    val resolvedEnable: ResiliencyTestSuite?
        get() = enable.resolveOrNull()

    companion object {
        fun fromSystemProperties() = ResiliencyTestsConfig(
            isResiliencyTestFlagEnabled = getBooleanValue(SPECMATIC_GENERATIVE_TESTS),
            isOnlyPositiveFlagEnabled = getBooleanValue(ONLY_POSITIVE)
        )

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
    @param:JsonProperty("bearer-file") val bearerFile: TemplateOrValue<String>? = null,
    @param:JsonProperty("bearer-environment-variable") val bearerEnvironmentVariable: TemplateOrValue<String>? = null,
    @param:JsonProperty("personal-access-token") @JsonAlias("personalAccessToken") val personalAccessToken: TemplateOrValue<String>? = null
) {
    @get:JsonIgnore
    val resolvedBearerFile: String
        get() = bearerFile.resolveOrNull() ?: "bearer.txt"

    @get:JsonIgnore
    val resolvedBearerEnvironmentVariable: String?
        get() = bearerEnvironmentVariable.resolveOrNull()

    @get:JsonIgnore
    val resolvedPersonalAccessToken: String?
        get() = personalAccessToken.resolveOrNull()
}

enum class PipelineProvider { azure }

data class Pipeline(
    val provider: TemplateOrValue<PipelineProvider>? = null,
    val organization: TemplateOrValue<String>? = null,
    val project: TemplateOrValue<String>? = null,
    val definitionId: TemplateOrValue<Int>? = null
) {
    @get:JsonIgnore
    val resolvedProvider: PipelineProvider
        get() = provider.resolveOrDefault(PipelineProvider.azure)

    @get:JsonIgnore
    val resolvedOrganization: String
        get() = organization.resolveOrDefault("")

    @get:JsonIgnore
    val resolvedProject: String
        get() = project.resolveOrDefault("")

    @get:JsonIgnore
    val resolvedDefinitionId: Int
        get() = definitionId.resolveOrDefault(0)

    fun getProvider(): PipelineProvider {
        return resolvedProvider
    }

    fun getOrganization(): String {
        return resolvedOrganization
    }

    fun getProject(): String {
        return resolvedProject
    }

    fun getDefinitionId(): Int {
        return resolvedDefinitionId
    }
}

data class Environment(
    val baseurls: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = null,
    val variables: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = null
) {
    @get:JsonIgnore
    val resolvedBaseurls: Map<String, String>
        get() = baseurls.resolveMapValuesOrEmpty()

    @get:JsonIgnore
    val resolvedVariables: Map<String, String>
        get() = variables.resolveMapValuesOrEmpty()
}

enum class SourceProvider { git, filesystem, web }

data class Source(
    @field:JsonAlias("type")
    val provider: TemplateOrValue<SourceProvider>? = null,
    val repository: TemplateOrValue<String>? = null,
    val branch: TemplateOrValue<String>? = null,
    @field:JsonAlias("provides")
    @JsonDeserialize(using = ConsumesDeserializer::class)
    val test: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>? = null,
    @field:JsonAlias("consumes")
    @JsonDeserialize(using = ConsumesDeserializer::class)
    val stub: TemplateOrValue<List<TemplateOrValue<SpecExecutionConfig>>>? = null,
    val directory: TemplateOrValue<String>? = null,
    val webBaseUrl: TemplateOrValue<String>? = null,
    val matchBranch: TemplateOrValue<Boolean>? = null,
) {
    constructor(test: List<String>? = null, stub: List<String>? = null) : this(
        test = test?.map { SpecExecutionConfig.StringValue(it.wrap()) }.wrapFullyOrNull(),
        stub = stub?.map { SpecExecutionConfig.StringValue(it.wrap()) }.wrapFullyOrNull()
    )

    @get:JsonIgnore
    val resolvedProvider: SourceProvider
        get() = provider.resolveOrDefault(filesystem)

    @get:JsonIgnore
    val resolvedRepository: String?
        get() = repository.resolveOrNull()

    @get:JsonIgnore
    val resolvedBranch: String?
        get() = branch.resolveOrNull()

    @get:JsonIgnore
    val resolvedTest: List<SpecExecutionConfig>?
        get() = test.resolveFullyOrNull()

    @get:JsonIgnore
    val resolvedStub: List<SpecExecutionConfig>?
        get() = stub.resolveFullyOrNull()

    @get:JsonIgnore
    val resolvedDirectory: String?
        get() = directory.resolveOrNull()

    @get:JsonIgnore
    val resolvedWebBaseUrl: String?
        get() = webBaseUrl.resolveOrNull()

    @get:JsonIgnore
    val resolvedMatchBranch: Boolean?
        get() = matchBranch.resolveOrNull()

    fun specsUsedAsStub(): List<String> {
        return resolvedStub.orEmpty().flatMap { it.specs() }
    }

    fun specsUsedAsTest(): List<String> {
        return resolvedTest.orEmpty().flatMap { it.specs() }
    }

    fun specToStubBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        return resolvedStub.orEmpty().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl) { configValue ->
                if(configValue.resolvedSpecType == SpecType.OPENAPI.value) OpenAPIMockConfig.from(configValue.resolvedConfig).baseUrl
                else null
            }
        }.toMap()
    }

    fun specToTestBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        val baseUrlFromConfig : (SpecExecutionConfig.ConfigValue) -> String? = {
            if(it.resolvedSpecType == SpecType.OPENAPI.value) OpenAPITestConfig.from(it.resolvedConfig).baseUrl
            else null
        }
        return resolvedTest.orEmpty().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl, baseUrlFromConfig)
        }.toMap()
    }

    fun specToTestGenerativeMap(): Map<String, ResiliencyTestSuite?> {
        return resolvedTest.orEmpty().flatMap {
            when (it) {
                is SpecExecutionConfig.StringValue -> listOf(it.resolvedValue to null)
                is SpecExecutionConfig.ObjectValue -> it.resolvedSpecs.map { specPath ->
                    specPath to it.resolvedResiliencyTests?.resolvedEnable
                }
                is SpecExecutionConfig.ConfigValue  -> it.resolvedSpecs.map { specPath ->
                    if(it.resolvedSpecType == SpecType.OPENAPI.value) specPath to OpenAPITestConfig.from(it.resolvedConfig).resiliencyTests?.resolvedEnable
                    else specPath to null
                }
            }
        }.toMap()
    }

    fun specToTestExamplesMap(): Map<String, List<String>> {
        return resolvedTest.orEmpty().flatMap {
            if(it is SpecExecutionConfig.ConfigValue && it.resolvedSpecType == SpecType.OPENAPI.value) {
                return@flatMap it.resolvedSpecs.map { specPath ->
                    specPath to OpenAPITestConfig.from(it.resolvedConfig).examples.orEmpty()
                }
            }
            emptyList()
        }.toMap()
    }

    fun specToStubExamplesMap(): Map<String, List<String>> {
        return resolvedStub.orEmpty().flatMap {
            if(it is SpecExecutionConfig.ConfigValue && it.resolvedSpecType == SpecType.OPENAPI.value) {
                return@flatMap it.resolvedSpecs.map { specPath ->
                    specPath to OpenAPIMockConfig.from(it.resolvedConfig).examples.orEmpty()
                }
            }
            emptyList()
        }.toMap()
    }


    fun getCanonicalTestConfigs(): List<SpecExecutionConfig> {
        if (this.test == null) return emptyList()
        val baseDirectory = getBaseDirectory()
        return this.resolvedTest.orEmpty().map { config ->  config.resolveAgainst(baseDirectory) }
    }

    fun getCanonicalStubConfigs(): List<SpecExecutionConfig> {
        if (this.stub == null) return emptyList()
        val baseDirectory = getBaseDirectory()
        return this.resolvedStub.orEmpty().map { config ->  config.resolveAgainst(baseDirectory) }
    }

    fun getBaseDirectory(): File {
        val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
        return when (resolvedProvider) {
            SourceProvider.web -> workingDirectory
            SourceProvider.filesystem -> workingDirectory.applyIf(resolvedDirectory) { resolve(it) }
            SourceProvider.git -> {
                val specmaticFolder = File(".").resolve(WorkingDirectory(DEFAULT_WORKING_DIRECTORY).path)
                val repository = resolvedRepository?.split("/")?.lastOrNull()?.removeSuffix(".git")
                if (repository != null) specmaticFolder.resolve("repos").resolve(repository) else workingDirectory
            }
        }
    }

    fun resolveSpecFile(specPath: String): File {
        val sourceBaseDir = getBaseDirectory()
        return if (resolvedProvider != web) {
            sourceBaseDir.resolve(specPath).canonicalFile
        } else {
            val cachedWebSpec = resolvedWebBaseUrl?.let { baseUrl ->
                ResolvedWebSource.localPathFor(
                    rootDir = sourceBaseDir.resolve(DEFAULT_WORKING_DIRECTORY).resolve("web"),
                    baseUrl = baseUrl,
                    specPath = specPath
                )
            }

            cachedWebSpec ?: try {
                val url = URL(specPath)
                sourceBaseDir.resolve("web").resolve(url.host).resolve(url.path.removePrefix("/")).canonicalFile
            } catch (_: MalformedURLException) {
                sourceBaseDir.resolve(specPath).canonicalFile
            }
        }
    }
}

data class RepositoryInfo(
    val provider: TemplateOrValue<String>,
    val collectionName: TemplateOrValue<String>
) {
    @get:JsonIgnore
    val resolvedProvider: String
        get() = provider.resolve()

    @get:JsonIgnore
    val resolvedCollectionName: String
        get() = collectionName.resolve()

    fun getProvider(): String {
        return provider.resolve()
    }

    fun getCollectionName(): String {
        return collectionName.resolve()
    }
}

interface ReportConfiguration {
    fun getSuccessCriteria(): SuccessCriteria
    fun excludedOpenAPIEndpoints(): List<String>

    companion object {
        val default = ReportConfigurationDetails(
           types = ReportTypes().wrap()
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportConfigurationDetails(
    val types: TemplateOrValue<ReportTypes>? = null
) : ReportConfiguration {

    @get:JsonIgnore
    val resolvedTypes: ReportTypes?
        get() = types.resolveOrNull()

    fun validatePresenceOfExcludedEndpoints(currentVersion: SpecmaticConfigVersion): ReportConfigurationDetails {
        if(currentVersion.isLessThanOrEqualTo(VERSION_1))
            return this

        if (resolvedTypes?.resolvedApiCoverage?.resolvedOpenAPI?.resolvedExcludedEndpoints.orEmpty().isNotEmpty()) {
            throw UnsupportedOperationException(excludedEndpointsWarning)
        }
        return this
    }

    fun clearPresenceOfExcludedEndpoints(): ReportConfigurationDetails {
        val currentTypes = resolvedTypes
        return this.copy(
            types = currentTypes?.copy(
                apiCoverage = currentTypes.resolvedApiCoverage?.copy(
                    openAPI = currentTypes.resolvedApiCoverage?.resolvedOpenAPI?.copy(
                        excludedEndpoints = emptyList<String>().wrapFullyOrNull()
                    )?.wrapOrNull()
                )?.wrapOrNull()
            )?.wrapOrNull()
        )
    }

    @JsonIgnore
    override fun getSuccessCriteria(): SuccessCriteria {
        return resolvedTypes?.resolvedApiCoverage?.resolvedOpenAPI?.resolvedSuccessCriteria ?: SuccessCriteria.default
    }

    @JsonIgnore
    override fun excludedOpenAPIEndpoints(): List<String> {
        return resolvedTypes?.resolvedApiCoverage?.resolvedOpenAPI?.resolvedExcludedEndpoints ?: emptyList()
    }
}

data class ReportTypes(
    @param:JsonProperty("APICoverage")
    val apiCoverage: TemplateOrValue<APICoverage>? = null
) {
    @get:JsonIgnore
    val resolvedApiCoverage: APICoverage?
        get() = apiCoverage.resolveOrNull()
}


data class APICoverage(
    @param:JsonProperty("OpenAPI")
    val openAPI: TemplateOrValue<APICoverageConfiguration>? = null
) {
    @get:JsonIgnore
    val resolvedOpenAPI: APICoverageConfiguration?
        get() = openAPI.resolveOrNull()
}

data class APICoverageConfiguration(
    val successCriteria: TemplateOrValue<SuccessCriteria>? = null,
    val excludedEndpoints: TemplateOrValue<List<TemplateOrValue<String>>>? = null
) {
    @get:JsonIgnore
    val resolvedSuccessCriteria: SuccessCriteria?
        get() = successCriteria.resolveOrNull()

    @get:JsonIgnore
    val resolvedExcludedEndpoints: List<String>
        get() = excludedEndpoints.resolveFullyOrEmpty()
}

data class SuccessCriteria(
    val minThresholdPercentage: TemplateOrValue<Int>? = null,
    val maxMissedEndpointsInSpec: TemplateOrValue<Int>? = null,
    val enforce: TemplateOrValue<Boolean>? = null
) {
    companion object {
        val default = SuccessCriteria(0.wrap(), 0.wrap(), false.wrap())
    }

    @JsonIgnore
    fun getMinThresholdPercentageOrDefault(): Int {
        return minThresholdPercentage.resolveOrDefault(0)
    }

    @JsonIgnore
    fun getMaxMissedEndpointsInSpecOrDefault(): Int {
        return maxMissedEndpointsInSpec.resolveOrDefault(0)
    }

    @JsonIgnore
    fun getEnforceOrDefault(): Boolean {
        return enforce.resolveOrDefault(false)
    }
}

data class SecurityConfiguration(
    @param:JsonProperty("OpenAPI")
    val openAPI: TemplateOrValue<OpenAPISecurityConfiguration>? = null
) {
    @get:JsonIgnore
    val resolvedOpenAPI: OpenAPISecurityConfiguration?
        get() = openAPI.resolveOrNull()

    fun getOpenAPISecurityScheme(scheme: String): SecuritySchemeConfiguration? {
        return resolvedOpenAPI?.resolvedSecuritySchemes?.get(scheme)
    }
}

data class OpenAPISecurityConfiguration(
    val securitySchemes: TemplateOrValue<Map<String, TemplateOrValue<SecuritySchemeConfiguration>>>? = null
) {
    @get:JsonIgnore
    val resolvedSecuritySchemes: Map<String, SecuritySchemeConfiguration>
        get() = securitySchemes.resolveMapValuesOrEmpty()
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OAuth2SecuritySchemeConfiguration::class, name = "oauth2"),
    JsonSubTypes.Type(value = BasicAuthSecuritySchemeConfiguration::class, name = "basicAuth"),
    JsonSubTypes.Type(value = BearerSecuritySchemeConfiguration::class, name = "bearer"),
    JsonSubTypes.Type(value = APIKeySecuritySchemeConfiguration::class, name = "apiKey")
)
sealed class SecuritySchemeConfiguration {
    abstract val type: TemplateOrValue<String>
}

interface SecuritySchemeWithOAuthToken {
    val token: TemplateOrValue<String>
}

@JsonTypeName("oauth2")
data class OAuth2SecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = "oauth2".wrap(),
    override val token: TemplateOrValue<String> = "".wrap()
) : SecuritySchemeConfiguration(), SecuritySchemeWithOAuthToken

@JsonTypeName("basicAuth")
data class BasicAuthSecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = "basicAuth".wrap(),
    val token: TemplateOrValue<String> = "".wrap()
) : SecuritySchemeConfiguration()

@JsonTypeName("bearer")
data class BearerSecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = "bearer".wrap(),
    override val token: TemplateOrValue<String> = "".wrap()
) : SecuritySchemeConfiguration(), SecuritySchemeWithOAuthToken

@JsonTypeName("apiKey")
data class APIKeySecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = "apiKey".wrap(),
    val value: TemplateOrValue<String> = "".wrap()
) : SecuritySchemeConfiguration()

fun loadSpecmaticConfigOrDefault(configFileName: String? = null): SpecmaticConfig {
    return loadSpecmaticConfigOrNull(configFileName).orDefault()
}

fun loadSpecmaticConfigOrNull(configFileName: String? = null): SpecmaticConfig? =
    loadSpecmaticConfigOrNull(configFileName, explicitlySpecifiedByUser = false)

fun loadSpecmaticConfigOrNull(
    configFileName: String? = null,
    explicitlySpecifiedByUser: Boolean = false
): SpecmaticConfig? {
    return if (configFileName == null) {
        SpecmaticConfigV1V2Common()
    } else {
        try {
            loadSpecmaticConfig(configFileName)
        } catch (e: ContractException) {
            val message = exceptionCauseMessage(e)
            val configFile = File(configFileName)

            if (!configFile.exists() && !explicitlySpecifiedByUser) {
                logger.debug(message)
            } else {
                logger.log(message)
            }

            null
        }
    }
}

fun loadSpecmaticConfig(configFileName: String? = null): SpecmaticConfig {
    val configFile = File(configFileName ?: configFilePath)

    if (!configFile.exists()) {
        throw ContractException("Could not find the Specmatic configuration at path ${configFile.canonicalPath}")
    }

    try {
        return configFile.toSpecmaticConfig()
    } catch(e: DatabindException) {
        throw Exception(configErrorMessage(e))
    } catch (e: Throwable) {
        throw Exception("Error parsing config: ${e.message}")
    }
}

fun loadSpecmaticConfigIfAvailableElseDefault(configFileName: String? = null): SpecmaticConfig {
    val configFile = File(configFileName ?: configFilePath).canonicalFile
    return loadSpecmaticConfigOrDefault(configFile.canonicalPath)
}

fun configErrorMessage(e: DatabindException): String {
    val location = if(e.location?.lineNr != null && e.location?.columnNr != null) {
        " (line ${e.location.lineNr}, column ${e.location.columnNr})"
    } else {
        ""
    }

    val errorPrefix = "Error parsing config$location"

    return when (e) {
        is InvalidNullException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix: $fieldPath must not be null, but found null."
        }

        is InvalidFormatException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            val actualValueClause = e.value?.let { " ($it is invalid)" }
            "$errorPrefix: $fieldPath accepts ${expectedType(e)}$actualValueClause"
        }

        is IgnoredPropertyException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix: $fieldPath is not a valid property in the configuration file."
        }

        is UnrecognizedPropertyException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            val knownProperties = e.knownPropertyIds.sortedBy { it.toString() }.joinToString(", ")
            "$errorPrefix: $fieldPath is not a valid property in the configuration file. Known properties are: $knownProperties."
        }

        is MismatchedInputException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix: $fieldPath accepts ${expectedType(e)}"
        }

        is JsonMappingException -> {
            val path = e.path
            val fieldPath = readablePath(path)
            "$errorPrefix at $fieldPath: ${e.originalMessage}"
        }

        else -> {
            "$errorPrefix: ${e.originalMessage}"
        }
    }
}

private fun expectedType(e: MismatchedInputException): String {
    val targetType = e.targetType ?: return "an object"

    return if (targetType.isEnum) {
        "one of the following: ${targetType.enumConstants.orEmpty().joinToString(", ") { it.toString() }}"
    } else if (targetType.isPrimitive) {
        targetType.simpleName.orEmpty().lowercase()
    } else if(targetType.genericSuperclass in listOf(java.lang.Number::class.java, java.lang.String::class.java, java.lang.Boolean::class.java)) {
        targetType.simpleName.orEmpty().lowercase()
    } else if (targetType.simpleName == "ArrayList") {
        "a list"
    } else {
        "an object"
    }
}

private fun readablePath(path: MutableList<JsonMappingException.Reference>) =
    path.joinToString(".") { it.fieldName ?: "[${it.index}]" }.replace(".[", "[")

fun readEnvVarOrProperty(
    envVarName: String,
    propertyName: String,
): String? = System.getenv(envVarName) ?: System.getProperty(propertyName)
