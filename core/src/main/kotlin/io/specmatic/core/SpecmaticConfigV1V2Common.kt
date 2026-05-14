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
import io.specmatic.core.config.v2.ConsumesDeserializer
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
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
import kotlin.collections.map
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
    fun getLenientMode(): Boolean? {
        return lenientMode?.resolve()
    }

    fun getGenerative(): Boolean? {
        return generative?.resolve()
    }

    fun getDelayInMilliseconds(): Long? {
        return delayInMilliseconds?.resolve() ?: getLongValue(SPECMATIC_STUB_DELAY)
    }

    fun getDictionary(): String? {
        return dictionary?.resolve() ?: getStringValue(SPECMATIC_STUB_DICTIONARY)
    }

    fun getIncludeMandatoryAndRequestedKeysInResponse(): Boolean? {
        return includeMandatoryAndRequestedKeysInResponse?.resolve()
    }

    fun getStartTimeoutInMilliseconds(): Long? {
        return startTimeoutInMilliseconds?.resolve()
    }

    fun getHotReload(): Switch? {
        return hotReload?.resolve()
    }

    fun getStrictMode(): Boolean? {
        return strictMode?.resolve() ?: Flags.getBooleanValueOrNull(Flags.STUB_STRICT_MODE)
    }

    fun getFilter(): String? {
        return filter?.resolve()
    }

    fun getHttps(): HttpsConfiguration? {
        return https?.resolve()
    }

    fun getGracefulRestartTimeoutInMilliseconds(): Long? {
        return gracefulRestartTimeoutInMilliseconds?.resolve()
    }

    fun getBaseUrl(): String? {
        return baseUrl?.resolve()
    }

    fun getCustomImplicitStubBase(): String? {
        return customImplicitStubBase?.resolve()
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

    fun getHost(): String? {
        return host?.resolve()
    }

    fun getPort(): Int? {
        return port?.resolve()
    }

    fun getSpecs(): List<String>? {
        return specs?.resolveFully()
    }

    fun getSpecsDirPath(): String? {
        return specsDirPath?.resolve()
    }

    fun getLogsDirPath(): String? {
        return logsDirPath?.resolve()
    }

    fun getLogMode(): VSLogMode? {
        return logMode?.resolve()
    }

    fun getNonPatchableKeys(): Set<String> {
        return nonPatchableKeys?.resolveFully().orEmpty()
    }
}

data class WorkflowIDOperation(
    val extract: TemplateOrValue<String>? = null,
    val use: TemplateOrValue<String>? = null
) {
    @JsonIgnore
    fun getExtract(): String? = extract?.resolve()

    @JsonIgnore
    fun getUse(): String? = use?.resolve()
}

interface WorkflowDetails {
    fun getExtractForAPI(apiDescription: String): String?
    fun getUseForAPI(apiDescription: String): String?

    companion object {
        val default: WorkflowDetails = WorkflowConfiguration()
    }
}

data class WorkflowConfiguration(val ids: TemplateOrValue<Map<String, TemplateOrValue<WorkflowIDOperation>>>? = null) : WorkflowDetails {
    constructor(ids: Map<String, WorkflowIDOperation>): this(ids = ids.wrapFully())

    private fun getOperation(operationId: String): WorkflowIDOperation? {
        return ids?.resolveFully()?.get(operationId)
    }

    override fun getExtractForAPI(apiDescription: String): String? {
        return getOperation(apiDescription)?.getExtract()
    }

    override fun getUseForAPI(apiDescription: String): String? {
        val operation = getOperation(apiDescription) ?: getOperation("*")
        return operation?.getUse()
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
    override fun getDefaultFields(): List<String> {
        return defaultFields?.resolveFully() ?: readEnvVarOrProperty(
            ATTRIBUTE_SELECTION_DEFAULT_FIELDS,
            ATTRIBUTE_SELECTION_DEFAULT_FIELDS
        ).orEmpty().split(",").filter { it.isNotBlank() }
    }

    override fun getQueryParamKey(): String {
        return queryParamKey?.resolve() ?: readEnvVarOrProperty(
            ATTRIBUTE_SELECTION_QUERY_PARAM_KEY,
            ATTRIBUTE_SELECTION_QUERY_PARAM_KEY
        ).orEmpty()
    }

    companion object {
        fun from(
            defaultFields: List<String>? = null,
            queryParamKey: String? = null
        ) = AttributeSelectionPattern(
            defaultFields = defaultFields?.wrapFully(),
            queryParamKey = queryParamKey?.let(::wrap)
        )
    }
}

data class SpecmaticConfigV1V2Common(
    val sources: TemplateOrValue<List<TemplateOrValue<Source>>>? = null,
    val auth: TemplateOrValue<Auth>? = null,
    val pipeline: TemplateOrValue<Pipeline>? = null,
    val environments: TemplateOrValue<Map<String, TemplateOrValue<Environment>>>? = null,
    val hooks: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = null,
    val proxy: TemplateOrValue<ProxyConfig>? = null,
    val repository: TemplateOrValue<RepositoryInfo>? = null,
    val report: TemplateOrValue<ReportConfigurationDetails>? = null,
    val security: TemplateOrValue<SecurityConfiguration>? = null,
    val test: TemplateOrValue<TestConfiguration>? = null,
    val stub: TemplateOrValue<StubConfiguration>? = null,
    val backwardCompatibility: TemplateOrValue<BackwardCompatibilityConfig>? = null,
    val virtualService: TemplateOrValue<VirtualServiceConfiguration>? = null,
    val examples: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val workflow: TemplateOrValue<WorkflowConfiguration>? = null,
    val ignoreInlineExamples: TemplateOrValue<Boolean>? = null,
    val ignoreInlineExampleWarnings: TemplateOrValue<Boolean>? = null,
    val schemaExampleDefault: TemplateOrValue<Boolean>? = null,
    val fuzzy: TemplateOrValue<Boolean>? = null,
    val extensibleQueryParams: TemplateOrValue<Boolean>? = null,
    val escapeSoapAction: TemplateOrValue<Boolean>? = null,
    val prettyPrint: TemplateOrValue<Boolean>? = null,
    val additionalExampleParamsFilePath: TemplateOrValue<String>? = null,
    val attributeSelectionPattern: TemplateOrValue<AttributeSelectionPattern>? = null,
    val allPatternsMandatory: TemplateOrValue<Boolean>? = null,
    val defaultPatternValues: TemplateOrValue<Map<String, TemplateOrValue<Any>>>? = null,
    val version: TemplateOrValue<SpecmaticConfigVersion>? = null,
    val disableTelemetry: TemplateOrValue<Boolean>? = null,
    val logging: TemplateOrValue<LoggingConfiguration>? = null,
    val mcp: TemplateOrValue<McpConfiguration>? = null,
    val licensePath: TemplateOrValue<Path>? = null,
    val reportDirPath: TemplateOrValue<Path>? = null,
    val globalSettings: TemplateOrValue<SpecmaticGlobalSettings>? = null,
) : SpecmaticConfig {
    private fun sourcesValue(): List<Source> = sources?.resolveFully().orEmpty()
    private fun authValue(): Auth? = auth?.resolve()
    private fun pipelineValue(): Pipeline? = pipeline?.resolve()
    private fun environmentsValue(): Map<String, Environment>? = environments?.resolveFully()
    private fun hooksValue(): Map<String, String> = hooks?.resolveFully().orEmpty()
    private fun proxyValue(): ProxyConfig? = proxy?.resolve()
    private fun repositoryValue(): RepositoryInfo? = repository?.resolve()
    private fun reportValue(): ReportConfigurationDetails? = report?.resolve()
    private fun securityValue(): SecurityConfiguration? = security?.resolve()
    private fun testValue(): TestConfiguration? = test?.resolve()
    private fun stubValue(): StubConfiguration? = stub?.resolve()
    private fun backwardCompatibilityValue(): BackwardCompatibilityConfig? = backwardCompatibility?.resolve()
    private fun virtualServiceValue(): VirtualServiceConfiguration? = virtualService?.resolve()
    private fun examplesValue(): List<String>? = examples?.resolveFully()
    private fun workflowValue(): WorkflowConfiguration? = workflow?.resolve()
    private fun ignoreInlineExamplesValue(): Boolean? = ignoreInlineExamples?.resolve()
    private fun ignoreInlineExampleWarningsValue(): Boolean? = ignoreInlineExampleWarnings?.resolve()
    private fun schemaExampleDefaultValue(): Boolean? = schemaExampleDefault?.resolve()
    private fun fuzzyValue(): Boolean? = fuzzy?.resolve()
    private fun extensibleQueryParamsValue(): Boolean? = extensibleQueryParams?.resolve()
    private fun escapeSoapActionValue(): Boolean? = escapeSoapAction?.resolve()
    private fun prettyPrintValue(): Boolean? = prettyPrint?.resolve()
    private fun additionalExampleParamsFilePathValue(): String? = additionalExampleParamsFilePath?.resolve()
    private fun attributeSelectionPatternValue(): AttributeSelectionPattern? = attributeSelectionPattern?.resolve()
    private fun allPatternsMandatoryValue(): Boolean? = allPatternsMandatory?.resolve()
    private fun defaultPatternValuesValue(): Map<String, Any> = defaultPatternValues?.resolveFully().orEmpty()
    private fun versionValue(): SpecmaticConfigVersion? = version?.resolve()
    private fun disableTelemetryValue(): Boolean? = disableTelemetry?.resolve()
    private fun loggingValue(): LoggingConfiguration? = logging?.resolve()
    private fun mcpValue(): McpConfiguration? = mcp?.resolve()
    private fun licensePathValue(): Path? = licensePath?.resolve()
    private fun reportDirPathValue(): Path? = reportDirPath?.resolve()
    private fun globalSettingsValue(): SpecmaticGlobalSettings? = globalSettings?.resolve()

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
            return specmaticConfig.reportValue()
        }

        @JsonIgnore
        fun getSources(specmaticConfig: SpecmaticConfigV1V2Common): List<Source> {
            return specmaticConfig.sourcesValue()
        }

        @JsonIgnore
        fun getRepository(specmaticConfig: SpecmaticConfigV1V2Common): RepositoryInfo? {
            return specmaticConfig.repositoryValue()
        }

        @JsonIgnore
        fun getPipeline(specmaticConfig: SpecmaticConfigV1V2Common): Pipeline? {
            return specmaticConfig.pipelineValue()
        }

        @JsonIgnore
        fun getSecurityConfiguration(specmaticConfig: SpecmaticConfigV1V2Common?): SecurityConfiguration? {
            return specmaticConfig?.securityValue()
        }

        @JsonIgnore
        fun getWorkflowConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): WorkflowConfiguration? {
            return specmaticConfig.workflowValue()
        }

        @JsonIgnore
        fun getTestConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): TestConfiguration? {
            return specmaticConfig.testValue()
        }

        @JsonIgnore
        fun getVirtualServiceConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): VirtualServiceConfiguration {
            return specmaticConfig.virtualServiceValue() ?: VirtualServiceConfiguration()
        }

        @JsonIgnore
        fun getAllPatternsMandatory(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? {
            return specmaticConfig.allPatternsMandatoryValue()
        }

        @JsonIgnore
        fun getIgnoreInlineExamples(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? {
            return specmaticConfig.ignoreInlineExamplesValue()
        }

        @JsonIgnore
        fun getAttributeSelectionPattern(specmaticConfig: SpecmaticConfigV1V2Common): AttributeSelectionPattern {
            return specmaticConfig.attributeSelectionPatternValue() ?: AttributeSelectionPattern()
        }

        @JsonIgnore
        fun getStubConfiguration(specmaticConfig: SpecmaticConfigV1V2Common): StubConfiguration {
            return specmaticConfig.stubValue() ?: StubConfiguration()
        }

        @JsonIgnore
        fun getTestConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): TestConfiguration? {
            return specmaticConfig.testValue()
        }

        @JsonIgnore
        fun getStubConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): StubConfiguration? {
            return specmaticConfig.stubValue()
        }

        @JsonIgnore
        fun getIgnoreInlineExampleWarningsOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.ignoreInlineExampleWarningsValue()

        @JsonIgnore
        fun getEscapeSoapActionOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.escapeSoapActionValue()

        @JsonIgnore
        fun getSchemaExampleDefaultOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.schemaExampleDefaultValue()

        @JsonIgnore
        fun getExtensibleQueryParamsOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.extensibleQueryParamsValue()

        @JsonIgnore
        fun getFuzzyMatchingEnabledOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.fuzzyValue()

        @JsonIgnore
        fun getPrettyPrintOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.prettyPrintValue()

        @JsonIgnore
        fun isTelemetryDisabledOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Boolean? = specmaticConfig.disableTelemetryValue()

        @JsonIgnore
        fun getReportDirPathOrNull(specmaticConfig: SpecmaticConfigV1V2Common): Path? = specmaticConfig.reportDirPathValue()

        @JsonIgnore
        fun getVirtualServiceConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): VirtualServiceConfiguration? {
            return specmaticConfig.virtualServiceValue()
        }

        @JsonIgnore
        fun getAttributeSelectionConfigOrNull(specmaticConfig: SpecmaticConfigV1V2Common): AttributeSelectionPattern? {
            return specmaticConfig.attributeSelectionPatternValue()
        }

        fun getEnvironments(specmaticConfig: SpecmaticConfigV1V2Common): Map<String, Environment>? {
            return specmaticConfig.environmentsValue()
        }

        @JsonIgnore
        fun getHooks(specmaticConfig: SpecmaticConfigV1V2Common): Map<String, String> {
            return specmaticConfig.hooksValue()
        }

        @JsonIgnore
        fun getProxyConfig(specmaticConfig: SpecmaticConfigV1V2Common): ProxyConfig? {
            return specmaticConfig.proxyValue()
        }

        @JsonIgnore
        fun getLogConfigurationOrNull(specmaticConfig: SpecmaticConfigV1V2Common): LoggingConfiguration? {
            return specmaticConfig.loggingValue()
        }
    }

    @JsonIgnore
    override fun getLogConfigurationOrDefault(): LoggingConfiguration {
        return this.loggingValue() ?: LoggingConfiguration.default()
    }

    @JsonIgnore
    override fun getSpecificationSources(): List<SpecificationSource> {
        val resiliencyTestSuite = testValue()?.getResiliencyTests()?.getEnabled()
        return this.sourcesValue().map { source ->
            val specificationSource = SpecificationSource(source.getProvider(), source.getRepository(), source.getDirectory(), source.getBranch(), source.getMatchBranch())
            val sourceBaseDir = source.getBaseDirectory()

            val withTestSources = source.getTest().fold(specificationSource) { acc, testExecutionConfig ->
                val testEntries = testExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir, resiliencyTestSuite)
                acc.copy(test = acc.test.plus(testEntries))
            }

            source.getStub().fold(withTestSources) { acc, mockExecutionConfig ->
                val mockEntries = mockExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir)
                acc.copy(mock = acc.mock.plus(mockEntries))
            }
        }
    }

    @JsonIgnore
    override fun getFirstMockSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        val resiliencyTestSuite = testValue()?.getResiliencyTests()?.getEnabled()
        return this.sourcesValue().firstNotNullOfOrNull { source ->
            val sourceBaseDir = source.getBaseDirectory()
            source.getStub().firstNotNullOfOrNull { testExecutionConfig ->
                val entries = testExecutionConfig.createSpecificationEntriesFrom(source, sourceBaseDir, resiliencyTestSuite)
                entries.firstOrNull(predicate)
            }
        }
    }

    @JsonIgnore
    override fun getFirstTestSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        return this.sourcesValue().firstNotNullOfOrNull { source ->
            val sourceBaseDir = source.getBaseDirectory()
            source.getTest().firstNotNullOfOrNull { testExecutionConfig ->
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
            ?: (disableTelemetryValue() == true)
    }

    @JsonIgnore
    override fun testConfigFor(specPath: String, specType: String): SpecmaticSpecConfig? {
        return sourcesValue().flatMap { it.getTest() }.configWith(specPath, specType)
    }

    @JsonIgnore
    override fun testConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig? {
        val resolvedTestConfig = this.sourcesValue().flatMap { it.getCanonicalTestConfigs() }
        return resolvedTestConfig.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull { config ->
            if (config.specType.resolve() != specType.value) return@firstOrNull false
            config.specs().any { specPath -> File(specPath).sameAs(file) }
        }?.config?.resolve().orEmpty().let(::SpecmaticSpecConfig)
    }

    @JsonIgnore
    override fun stubConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig? {
        val resolvedTestConfig = this.sourcesValue().flatMap { it.getCanonicalStubConfigs() }
        return resolvedTestConfig.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull { config ->
            if (config.specType.resolve() != specType.value) return@firstOrNull false
            config.specs().any { specPath -> File(specPath).sameAs(file) }
        }?.config?.resolve().orEmpty().let(::SpecmaticSpecConfig)
    }

    @JsonIgnore
    override fun stubConfigFor(specPath: String, specType: String): SpecmaticSpecConfig? {
        return sourcesValue().flatMap { it.getStub() }.configWith(specPath, specType)
    }

    @JsonIgnore
    private fun List<SpecExecutionConfig>.configWith(specPath: String, specType: String): SpecmaticSpecConfig? {
        return this.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull {
            it.contains(specPath, specType)
        }?.config?.resolve().orEmpty().let(::SpecmaticSpecConfig)
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
            sourceProvider = source.getProvider().name,
            repository = source.getRepository().orEmpty(),
            branch = source.getBranch() ?: "main",
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
            report = reportValue()?.clearPresenceOfExcludedEndpoints()?.let { TemplateOrValue.Value(it) }
        )
    }

    @JsonIgnore
    override fun getReport(): ReportConfiguration? {
        return reportValue()
    }

    @JsonIgnore
    override fun getWorkflowDetails(): WorkflowDetails? {
        return workflowValue()
    }

    @JsonIgnore
    override fun getAttributeSelectionPattern(): AttributeSelectionPatternDetails {
        return getAttributeSelectionPattern(this)
    }

    @JsonIgnore
    override fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings {
        return this.globalSettingsValue() ?: SpecmaticGlobalSettings()
    }

    @JsonIgnore
    override fun stubBaseUrls(defaultBaseUrl: String): List<String> =
        sourcesValue()
            .flatMap { source ->
                source.getStub().map { consumes ->
                    baseUrlFrom(consumes, defaultBaseUrl)
                }
            }.distinct()

    @JsonIgnore
    override fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
        return sourcesValue().flatMap { source ->
            source.getStub().flatMap { consumes ->
                when (consumes) {
                    is SpecExecutionConfig.StringValue -> consumes.specs().map { it to defaultBaseUrl }
                    is SpecExecutionConfig.ObjectValue -> consumes.specs().map { it to consumes.toBaseUrl(defaultBaseUrl) }
                    is SpecExecutionConfig.ConfigValue -> consumes.specs().map { it to defaultBaseUrl }
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

        sourcesValue().forEach { source ->
            logger.log("In central repo ${source.getRepository()}")

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
        return sourcesValue().map { source ->
            val defaultBaseUrl = getStubConfiguration(this).getBaseUrl() ?: getStringValue(SPECMATIC_BASE_URL) ?: Configuration.DEFAULT_BASE_URL
            val stubExamplesMap = source.specToStubExamplesMap()
            val stubPaths = source.specToStubBaseUrlMap(defaultBaseUrl).entries.map { ContractSourceEntry(it.key, it.value, exampleDirPaths = stubExamplesMap[it.key]) }

            val testBaseUrlMap = source.specToTestBaseUrlMap(defaultBaseUrl)
            val testGenerativeMap = source.specToTestGenerativeMap()
            val testExamplesMap = source.specToTestExamplesMap()
            val testPaths = testBaseUrlMap.entries.map { ContractSourceEntry(it.key, it.value, testGenerativeMap[it.key], exampleDirPaths = testExamplesMap[it.key]) }

            val sourceMatchBranch = source.getMatchBranch() ?: false
            val effectiveUseCurrentBranch = useCurrentBranchForCentralRepo || sourceMatchBranch
            val effectiveBranch = getEffectiveBranchForSource(source.getBranch(), effectiveUseCurrentBranch)


            when (source.getProvider()) {
                git -> when (source.getRepository()) {
                    null -> GitMonoRepo(testPaths, stubPaths, source.getProvider().toString())
                    else -> GitRepo(
                        source.getRepository().orEmpty(),
                        effectiveBranch,
                        testPaths,
                        stubPaths,
                        source.getProvider().toString(),
                        effectiveUseCurrentBranch,
                        specmaticConfig = this
                    )
                }

                filesystem -> LocalFileSystemSource(source.getDirectory() ?: ".", testPaths, stubPaths)

                web -> source.getWebBaseUrl()?.let { ResolvedWebSource(it, testPaths, stubPaths) } ?: WebSource(testPaths, stubPaths)
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
        return testValue()?.getAllowExtensibleSchema() ?: getBooleanValue(EXTENSIBLE_SCHEMA)
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
        return testValue()?.getValidateResponseValues() ?: getBooleanValue(VALIDATE_RESPONSE_VALUE)
    }

    @JsonIgnore
    override fun parsedDefaultPatternValues(): Map<String, Value> {
        return parsedJSONObject(ObjectMapper().writeValueAsString(defaultPatternValuesValue())).jsonObject
    }

    @JsonIgnore
    override fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        return (testValue()?.getResiliencyTests() ?: ResiliencyTestsConfig.fromSystemProperties()).getEnabled() ?: ResiliencyTestSuite.none
    }

    @JsonIgnore
    override fun getTestTimeoutInMilliseconds(): Long? {
        return testValue()?.getTimeoutInMilliseconds() ?: getLongValue(SPECMATIC_TEST_TIMEOUT)
    }

    @JsonIgnore
    override fun getMaxTestRequestCombinations(): Int? {
        val configValue = if (getVersion() == VERSION_2) testValue()?.getMaxTestRequestCombinations() else null
        return configValue ?: getIntValue(MAX_TEST_REQUEST_COMBINATIONS)
    }

    @JsonIgnore
    override fun getTestStrictMode(): Boolean? {
        return testValue()?.getStrictMode() ?: getStringValue(TEST_STRICT_MODE)?.toBoolean()
    }

    @JsonIgnore
    override fun getTestLenientMode(): Boolean? {
        return testValue()?.getLenientMode() ?: getStringValue(TEST_LENIENT_MODE)?.toBoolean()
    }

    @JsonIgnore
    override fun getTestParallelism(): String? {
        return testValue()?.getParallelism() ?: getStringValue(SPECMATIC_TEST_PARALLELISM)
    }

    @JsonIgnore
    override fun getTestsDirectory(): String? {
        return testValue()?.getTestsDirectory()
            ?: readEnvVarOrProperty(TESTS_DIRECTORY_ENV_VAR, TESTS_DIRECTORY_PROPERTY)
    }

    @JsonIgnore
    override fun getMaxTestCount(): Int? {
        return testValue()?.getMaxTestCount() ?: getIntValue(MAX_TEST_COUNT)
    }

    @JsonIgnore
    override fun getTestFilter(): String? {
        return getTestConfiguration(this)?.getFilter()
            ?: readEnvVarOrProperty(TEST_FILTER_ENV_VAR, TEST_FILTER_PROPERTY)
    }

    @JsonIgnore
    override fun getTestFilterName(): String? {
        val configValue = if (getVersion() == VERSION_2) testValue()?.getFilterName() else null
        return configValue ?: readEnvVarOrProperty(TEST_FILTER_NAME_ENV_VAR, TEST_FILTER_NAME_PROPERTY)
    }

    @JsonIgnore
    override fun getTestFilterNotName(): String? {
        val configValue = if (getVersion() == VERSION_2) testValue()?.getFilterNotName() else null
        return configValue ?: readEnvVarOrProperty(TEST_FILTER_NOT_NAME_ENV_VAR, TEST_FILTER_NOT_NAME_PROPERTY)
    }

    @JsonIgnore
    override fun getTestOverlayFilePath(specFile: File, specType: SpecType): String? {
        val configValue = if (getVersion() == VERSION_2) testValue()?.getOverlayFilePath() else null
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
        val configValue = if (getVersion() == VERSION_2) testValue()?.getBaseUrl() else null
        return configValue ?: readEnvVarOrProperty(TEST_BASE_URL_ENV_VAR, TEST_BASE_URL_PROPERTY)
    }

    @JsonIgnore
    override fun getTestSwaggerUrl(): String? {
        return getTestConfiguration(this)?.getSwaggerUrl()
    }

    @JsonIgnore
    override fun getTestSwaggerUIBaseUrl(): String? {
        val configValue = if (getVersion() == VERSION_2) testValue()?.getSwaggerUIBaseURL() else null
        return configValue ?: readEnvVarOrProperty(TEST_SWAGGER_UI_BASEURL_ENV_VAR, TEST_SWAGGER_UI_BASEURL_PROPERTY)
    }

    @JsonIgnore
    override fun getTestJunitReportDir(): String? {
        return if (getVersion() == VERSION_2) testValue()?.getJunitReportDir() else null
    }

    @JsonIgnore
    override fun getActuatorUrl(): String? {
        return getTestConfiguration(this)?.getActuatorUrl() ?: getStringValue(TEST_ENDPOINTS_API)
    }

    @JsonIgnore
    override fun enableResiliencyTests(onlyPositive: Boolean): SpecmaticConfigV1V2Common {
        val testConfig = testValue() ?: TestConfiguration()
        return this.copy(
            test = TemplateOrValue.Value(testConfig.copy(
                resiliencyTests = (testConfig.getResiliencyTests() ?: ResiliencyTestsConfig.fromSystemProperties()).copy(
                    enable = wrap(if (onlyPositive) ResiliencyTestSuite.positiveOnly else ResiliencyTestSuite.all)
                ).let(::wrap)
            ))
        )
    }

    override fun disableResiliencyTests(): SpecmaticConfig {
        val testConfig = testValue() ?: TestConfiguration()
        val resiliencyTestsConfig = ResiliencyTestsConfig(enable = wrap(ResiliencyTestSuite.none))
        return this.copy(test = TemplateOrValue.Value(testConfig.copy(resiliencyTests = wrap(resiliencyTestsConfig))))
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
        val httpsConfiguration = testValue()?.getHttps() ?: return registry
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
        return ignoreInlineExamplesValue() ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES)
    }

    @JsonIgnore
    override fun getIgnoreInlineExampleWarnings(): Boolean {
        return ignoreInlineExampleWarningsValue() ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS)
    }

    @JsonIgnore
    override fun getAllPatternsMandatory(): Boolean {
        return allPatternsMandatoryValue() ?: getBooleanValue(Flags.ALL_PATTERNS_MANDATORY)
    }

    @JsonIgnore
    override fun getSchemaExampleDefault(): Boolean {
        val configValue = if (getVersion() == VERSION_2) schemaExampleDefaultValue() else null
        return configValue ?: getBooleanValue(Flags.SCHEMA_EXAMPLE_DEFAULT)
    }

    @JsonIgnore
    override fun getFuzzyMatchingEnabled(): Boolean {
        val configValue = if (getVersion() == VERSION_2) fuzzyValue() else null
        return configValue ?: getBooleanValue(Flags.SPECMATIC_FUZZY)
    }

    @JsonIgnore
    override fun getExtensibleQueryParams(): Boolean {
        val configValue = if (getVersion() == VERSION_2) extensibleQueryParamsValue() else null
        return configValue ?: getBooleanValue(EXTENSIBLE_QUERY_PARAMS)
    }

    @JsonIgnore
    override fun getEscapeSoapAction(): Boolean {
        val configValue = if (getVersion() == VERSION_2) escapeSoapActionValue() else null
        return configValue ?: getBooleanValue(SPECMATIC_ESCAPE_SOAP_ACTION)
    }

    @JsonIgnore
    override fun getPrettyPrint(): Boolean {
        val configValue = if (getVersion() == VERSION_2) prettyPrintValue() else null
        return configValue ?: getBooleanValue(SPECMATIC_PRETTY_PRINT, true)
    }

    @JsonIgnore
    override fun getAdditionalExampleParamsFilePath(): String? {
        return additionalExampleParamsFilePathValue() ?: getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)
    }

    @JsonIgnore
    override fun getHooks(): Map<String, String> {
        return hooksValue()
    }

    override fun getStubHooks(): List<FileAssociation<Map<String, String>>> {
        return listOf(FileAssociation.Global(this.hooksValue()))
    }

    @JsonIgnore
    override fun getProxyConfig(): ProxyConfig? {
        return proxyValue()
    }

    @JsonIgnore
    override fun getDefaultPatternValues(): Map<String, Any> {
        return defaultPatternValuesValue()
    }

    override fun getVersion(): SpecmaticConfigVersion {
        return this.versionValue() ?: VERSION_1
    }

    @JsonIgnore
    override fun getMatchBranchEnabled(): Boolean {
        return sourcesValue().any { it.getMatchBranch() == true } || getBooleanValue(Flags.MATCH_BRANCH)
    }

    @JsonIgnore
    override fun getAuth(repositoryUrl: String): Auth? {
        return authValue()
    }

    @JsonIgnore
    fun getAuth(): Auth? = authValue()

    @JsonIgnore
    override fun getAuthBearerFile(repositoryUrl: String): String? {
        return authValue()?.getBearerFile()
    }

    @JsonIgnore
    override fun getAuthBearerEnvironmentVariable(repositoryUrl: String): String? {
        return authValue()?.getBearerEnvironmentVariable()
    }

    @JsonIgnore
    override fun getAuthPersonalAccessToken(repositoryUrl: String): String? {
        val tokenFromConfig = authValue()?.getPersonalAccessToken()?.takeIf { it.isNotBlank() }
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
        return examplesValue() ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    override fun getStubExampleDirs(specFile: File): List<String> {
        return examplesValue() ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    override fun getExamples(): List<String> {
        return examplesValue() ?: getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList()
    }

    @JsonIgnore
    override fun getRepositoryProvider(): String? {
        return repositoryValue()?.getProvider()
    }

    @JsonIgnore
    override fun getRepositoryCollectionName(): String? {
        return repositoryValue()?.getCollectionName()
    }

    @JsonIgnore
    override fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig? {
        return this.backwardCompatibilityValue()
    }

    @JsonIgnore
    override fun getMcpConfiguration(): McpConfiguration? {
        return this.mcpValue()
    }

    @JsonIgnore
    override fun getPipelineProvider(): PipelineProvider? {
        return pipelineValue()?.getProvider()
    }

    @JsonIgnore
    override fun getPipelineDefinitionId(): Int? {
        return pipelineValue()?.getDefinitionId()
    }

    @JsonIgnore
    override fun getPipelineOrganization(): String? {
        return pipelineValue()?.getOrganization()
    }

    @JsonIgnore
    override fun getPipelineProject(): String? {
        return pipelineValue()?.getProject()
    }

    @JsonIgnore
    override fun getOpenAPISecurityConfigurationScheme(specFile: File, scheme: String): SecuritySchemeConfiguration? {
        return securityValue()?.getOpenAPISecurityScheme(scheme)
    }

    @JsonIgnore
    override fun getBasicAuthSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? BasicAuthSecuritySchemeConfiguration)?.token?.resolve()
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_BASIC_AUTH_TOKEN)
    }

    @JsonIgnore
    override fun getBearerSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? SecuritySchemeWithOAuthToken)?.token?.resolve()
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_OAUTH2_TOKEN)
    }

    @JsonIgnore
    override fun getApiKeySecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? APIKeySecuritySchemeConfiguration)?.value?.resolve()
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
        return sourcesValue().flatMap { source ->
            source.getStub().flatMap { stub ->
                stub.specs()
            }.map { spec ->
                if (source.getProvider() == web) spec
                else spec.canonicalPath(relativeTo)
            }
        }
    }

    override fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfigV1V2Common {
        val reportConfigurationDetails = reportConfiguration as? ReportConfigurationDetails ?: return this
        return this.copy(report = TemplateOrValue.Value(reportConfigurationDetails))
    }

    override fun getEnvironment(envName: String): JSONObjectValue {
        val envConfigFromFile = environmentsValue()?.get(envName) ?: return JSONObjectValue()

        try {
            return parsedJSONObject(content = ObjectMapper().writeValueAsString(envConfigFromFile))
        } catch(e: Throwable) {
            throw ContractException("Error loading Specmatic configuration: ${e.message}")
        }
    }

    override fun enableResiliencyTests(): SpecmaticConfigV1V2Common {
        val testConfig = testValue() ?: TestConfiguration()
        return this.copy(
            test = TemplateOrValue.Value(testConfig.copy(
                resiliencyTests = (testConfig.getResiliencyTests() ?: ResiliencyTestsConfig()).copy(
                    enable = wrap(ResiliencyTestSuite.none),
                ).let(::wrap),
            )),
        )
    }

    override fun withTestModes(strictMode: Boolean?, lenientMode: Boolean?): SpecmaticConfigV1V2Common {
        val testConfig = testValue() ?: TestConfiguration()
        return this.copy(
            test = TemplateOrValue.Value(testConfig.copy(
                strictMode = (strictMode ?: testConfig.getStrictMode())?.let(::wrap),
                lenientMode = (lenientMode ?: testConfig.getLenientMode())?.let(::wrap),
            )),
        )
    }

    override fun withTestBaseURL(testBaseURL: String): SpecmaticConfig {
        val testConfig = testValue() ?: TestConfiguration()
        return this.copy(test = TemplateOrValue.Value(testConfig.copy(baseUrl = TemplateOrValue.Value(testBaseURL))))
    }

    override fun withTestFilter(filter: String?): SpecmaticConfigV1V2Common {
        if (filter == null) return this
        val testConfig = this.testValue() ?: TestConfiguration()
        return this.copy(test = TemplateOrValue.Value(testConfig.copy(filter = TemplateOrValue.Value(filter))))
    }

    override fun withTestTimeout(timeoutInMilliseconds: Long?): SpecmaticConfigV1V2Common {
        if (timeoutInMilliseconds == null) return this
        val testConfig = this.testValue() ?: TestConfiguration()
        return this.copy(test = TemplateOrValue.Value(testConfig.copy(timeoutInMilliseconds = TemplateOrValue.Value(timeoutInMilliseconds))))
    }

    override fun withStubModes(strictMode: Boolean?): SpecmaticConfigV1V2Common {
        if (strictMode == null) return this
        val stubConfig = this.stubValue() ?: StubConfiguration()
        return this.copy(stub = TemplateOrValue.Value(stubConfig.copy(strictMode = wrap(strictMode))))
    }

    override fun withStubFilter(filter: String?): SpecmaticConfigV1V2Common {
        if (filter == null) return this
        val stubConfig = this.stubValue() ?: StubConfiguration()
        return this.copy(stub = TemplateOrValue.Value(stubConfig.copy(filter = wrap(filter))))
    }

    override fun withGlobalMockDelay(delayInMilliseconds: Long): SpecmaticConfigV1V2Common {
        val stubConfig = this.stubValue() ?: StubConfiguration()
        return this.copy(stub = TemplateOrValue.Value(stubConfig.copy(delayInMilliseconds = wrap(delayInMilliseconds))))
    }

    override fun withMatchBranch(matchBranch: Boolean): SpecmaticConfig {
        val transformedSources = this.sourcesValue().map { source -> source.copy(matchBranch = wrap(matchBranch)) }
        return this.copy(sources = transformedSources.wrapFully())
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
        return sourcesValue().firstOrNull { source ->
            source.firstTestSpecMatching(specFile) != null
        }
    }

    @JsonIgnore
    private fun stubSourceFromConfig(specFile: File): Source? {
        return sourcesValue().firstOrNull { source ->
            source.firstStubSpecMatching(specFile) != null
        }
    }

    @JsonIgnore
    private fun String.canonicalPath(relativeTo: File): String {
        return relativeTo.parentFile?.resolve(this)?.canonicalPath ?: File(this).canonicalPath
    }

    @JsonIgnore
    override fun getLicensePath(): Path? {
        return licensePathValue()
    }

    @JsonIgnore
    override fun getReportDirPath(suffix: String?): Path {
        val resolvedReportDirPath = reportDirPathValue() ?: defaultReportDirPath
        return if (suffix == null) resolvedReportDirPath
        else resolvedReportDirPath.resolve(suffix)
    }

    override fun plusExamples(exampleDirectories: List<String>): SpecmaticConfig {
        return copy(examples = examplesValue().orEmpty().plus(exampleDirectories).wrapFully())
    }

    @JsonIgnore
    override fun getSecurityConfiguration(specFile: File): SecurityConfiguration? {
        return securityValue()
    }

    private fun File.sameAs(other: File): Boolean {
        return try {
            this.toPath().toRealPath() == other.toPath().toRealPath()
        } catch (_: Exception) {
            this.canonicalFile == other.canonicalFile
        }
    }

    private fun Source.firstTestSpecMatching(specFile: File): String? {
        return getTest().asSequence().flatMap { it.specs().asSequence() }.firstOrNull { specPath ->
            resolveSpecFile(specPath).sameAs(specFile)
        }
    }

    private fun Source.firstStubSpecMatching(specFile: File): String? {
        return getStub().asSequence().flatMap { it.specs().asSequence() }.firstOrNull { specPath ->
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
) {
    fun getResiliencyTests(): ResiliencyTestsConfig? = resiliencyTests?.resolve()
    fun getValidateResponseValues(): Boolean? = validateResponseValues?.resolve()
    fun getAllowExtensibleSchema(): Boolean? = allowExtensibleSchema?.resolve()
    fun getTimeoutInMilliseconds(): Long? = timeoutInMilliseconds?.resolve()
    fun getStrictMode(): Boolean? = strictMode?.resolve()
    fun getLenientMode(): Boolean? = lenientMode?.resolve()
    fun getParallelism(): String? = parallelism?.resolve()
    fun getMaxTestRequestCombinations(): Int? = maxTestRequestCombinations?.resolve()
    fun getMaxTestCount(): Int? = maxTestCount?.resolve()
    fun getTestsDirectory(): String? = testsDirectory?.resolve()
    fun getSwaggerUrl(): String? = swaggerUrl?.resolve()
    fun getSwaggerUIBaseURL(): String? = swaggerUIBaseURL?.resolve()
    fun getActuatorUrl(): String? = actuatorUrl?.resolve()
    fun getFilter(): String? = filter?.resolve()
    fun getBaseUrl(): String? = baseUrl?.resolve()
    fun getFilterName(): String? = filterName?.resolve()
    fun getFilterNotName(): String? = filterNotName?.resolve()
    fun getOverlayFilePath(): String? = overlayFilePath?.resolve()
    fun getJunitReportDir(): String? = junitReportDir?.resolve()
    fun getHttps(): HttpsConfiguration? = https?.resolve()
}

enum class ResiliencyTestSuite {
    all, positiveOnly, none
}

data class ResiliencyTestsConfig(
    val enable: TemplateOrValue<ResiliencyTestSuite>? = null
) {
    constructor(resiliencyTestSuite: ResiliencyTestSuite): this(wrap(resiliencyTestSuite))
    constructor(isResiliencyTestFlagEnabled: Boolean, isOnlyPositiveFlagEnabled: Boolean) : this(
        enable = getEnableFrom(isResiliencyTestFlagEnabled, isOnlyPositiveFlagEnabled)?.let(::wrap)
    )

    @JsonIgnore
    fun getEnabled(): ResiliencyTestSuite? = enable?.resolve()

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
    @param:JsonProperty("bearer-file") val bearerFile: TemplateOrValue<String> = wrap("bearer.txt"),
    @param:JsonProperty("bearer-environment-variable") val bearerEnvironmentVariable: TemplateOrValue<String>? = null,
    @param:JsonProperty("personal-access-token") @JsonAlias("personalAccessToken") val personalAccessToken: TemplateOrValue<String>? = null
) {
    @JsonIgnore
    fun getBearerFile(): String = bearerFile.resolve()

    @JsonIgnore
    fun getBearerEnvironmentVariable(): String? = bearerEnvironmentVariable?.resolve()

    @JsonIgnore
    fun getPersonalAccessToken(): String? = personalAccessToken?.resolve()
}

enum class PipelineProvider { azure }

data class Pipeline(
    val provider: TemplateOrValue<PipelineProvider> = wrap(PipelineProvider.azure),
    val organization: TemplateOrValue<String> = wrap(""),
    val project: TemplateOrValue<String> = wrap(""),
    val definitionId: TemplateOrValue<Int> = wrap(0)
) {
    fun getProvider(): PipelineProvider {
        return provider.resolve()
    }

    fun getOrganization(): String {
        return organization.resolve()
    }

    fun getProject(): String {
        return project.resolve()
    }

    fun getDefinitionId(): Int {
        return definitionId.resolve()
    }
}

data class Environment(
    val baseurls: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = null,
    val variables: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = null
) {
    @JsonIgnore
    fun getBaseUrls(): Map<String, String>? = baseurls?.resolveFully()

    @JsonIgnore
    fun getVariables(): Map<String, String>? = variables?.resolveFully()
}

enum class SourceProvider { git, filesystem, web }

data class Source(
    @field:JsonAlias("type")
    val provider: TemplateOrValue<SourceProvider> = wrap(filesystem),
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
        test = test?.map { SpecExecutionConfig.StringValue(wrap(it)) }?.wrapFully(),
        stub = stub?.map { SpecExecutionConfig.StringValue(wrap(it)) }?.wrapFully()
    )

    fun getProvider(): SourceProvider = provider.resolve()
    fun getRepository(): String? = repository?.resolve()
    fun getBranch(): String? = branch?.resolve()
    fun getTest(): List<SpecExecutionConfig> = test?.resolveFully().orEmpty()
    fun getStub(): List<SpecExecutionConfig> = stub?.resolveFully().orEmpty()
    fun getDirectory(): String? = directory?.resolve()
    fun getWebBaseUrl(): String? = webBaseUrl?.resolve()
    fun getMatchBranch(): Boolean? = matchBranch?.resolve()

    fun specsUsedAsStub(): List<String> {
        return getStub().flatMap { it.specs() }
    }

    fun specsUsedAsTest(): List<String> {
        return getTest().flatMap { it.specs() }
    }

    fun specToStubBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        return getStub().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl) { configValue ->
                if(configValue.specType.resolve() == SpecType.OPENAPI.value) OpenAPIMockConfig.from(configValue.config.resolve()).baseUrl
                else null
            }
        }.toMap()
    }

    fun specToTestBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        val baseUrlFromConfig : (SpecExecutionConfig.ConfigValue) -> String? = {
            if(it.specType.resolve() == SpecType.OPENAPI.value) OpenAPITestConfig.from(it.config.resolve()).baseUrl
            else null
        }

        return getTest().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl, baseUrlFromConfig)
        }.toMap()
    }

    fun specToTestGenerativeMap(): Map<String, ResiliencyTestSuite?> {
        return getTest().flatMap {
            when (it) {
                is SpecExecutionConfig.StringValue -> it.specs().map { specPath -> specPath to null }
                is SpecExecutionConfig.ObjectValue -> it.specs().map { specPath ->
                    specPath to it.resolvedResiliencyTests()?.getEnabled()
                }
                is SpecExecutionConfig.ConfigValue  -> it.specs().map { specPath ->
                    if(it.specType.resolve() == SpecType.OPENAPI.value) specPath to OpenAPITestConfig.from(it.config.resolve()).resiliencyTests?.getEnabled()
                    else specPath to null
                }
            }
        }.toMap()
    }

    fun specToTestExamplesMap(): Map<String, List<String>> {
        return getTest().flatMap {
            if(it is SpecExecutionConfig.ConfigValue && it.specType.resolve() == SpecType.OPENAPI.value) {
                return@flatMap it.specs().map { specPath ->
                    specPath to OpenAPITestConfig.from(it.config.resolve()).examples.orEmpty()
                }
            }
            emptyList()
        }.toMap()
    }

    fun specToStubExamplesMap(): Map<String, List<String>> {
        return getStub().flatMap {
            if(it is SpecExecutionConfig.ConfigValue && it.specType.resolve() == SpecType.OPENAPI.value) {
                return@flatMap it.specs().map { specPath ->
                    specPath to OpenAPIMockConfig.from(it.config.resolve()).examples.orEmpty()
                }
            }
            emptyList()
        }.toMap()
    }


    fun getCanonicalTestConfigs(): List<SpecExecutionConfig> {
        if (this.test == null) return emptyList()
        val baseDirectory = getBaseDirectory()
        return this.test.resolveFully().map { config ->  config.resolveAgainst(baseDirectory) }
    }

    fun getCanonicalStubConfigs(): List<SpecExecutionConfig> {
        if (this.stub == null) return emptyList()
        val baseDirectory = getBaseDirectory()
        return this.stub.resolveFully().map { config ->  config.resolveAgainst(baseDirectory) }
    }

    fun getBaseDirectory(): File {
        val workingDirectory = File(getConfigFilePath()).parentFile ?: File(".")
        return when (provider.resolve()) {
            SourceProvider.web -> workingDirectory
            SourceProvider.filesystem -> workingDirectory.applyIf(directory) { resolve(it.resolve()) }
            SourceProvider.git -> {
                val specmaticFolder = File(".").resolve(WorkingDirectory(DEFAULT_WORKING_DIRECTORY).path)
                val repository = repository?.resolve()?.split("/")?.lastOrNull()?.removeSuffix(".git")
                if (repository != null) specmaticFolder.resolve("repos").resolve(repository) else workingDirectory
            }
        }
    }

    fun resolveSpecFile(specPath: String): File {
        val sourceBaseDir = getBaseDirectory()
        return if (provider.resolve() != web) {
            sourceBaseDir.resolve(specPath).canonicalFile
        } else {
            val cachedWebSpec = webBaseUrl?.let { baseUrl ->
                ResolvedWebSource.localPathFor(
                    rootDir = sourceBaseDir.resolve(DEFAULT_WORKING_DIRECTORY).resolve("web"),
                    baseUrl = baseUrl.resolve(),
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
        val default = ReportConfigurationDetails(types = wrap(ReportTypes()))
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportConfigurationDetails(
    val types: TemplateOrValue<ReportTypes>? = null
) : ReportConfiguration {

    fun validatePresenceOfExcludedEndpoints(currentVersion: SpecmaticConfigVersion): ReportConfigurationDetails {
        if(currentVersion.isLessThanOrEqualTo(VERSION_1))
            return this

        val openAPI = types?.resolve()?.apiCoverage?.resolve()?.openAPI?.resolve()
        if (openAPI?.excludedEndpoints?.resolveFully().orEmpty().isNotEmpty()) {
            throw UnsupportedOperationException(excludedEndpointsWarning)
        }

        return this
    }

    fun clearPresenceOfExcludedEndpoints(): ReportConfigurationDetails {
        val resolvedTypes = types?.resolve() ?: return this
        val apiCoverage = resolvedTypes.apiCoverage?.resolve() ?: return this
        val openAPI = apiCoverage.openAPI?.resolve() ?: return this
        return this.copy(
            types = resolvedTypes.copy(
                apiCoverage = apiCoverage.copy(
                    openAPI = openAPI.copy(excludedEndpoints = wrap(emptyList())).let(::wrap)
                ).let(::wrap)
            ).let(::wrap)
        )
    }

    @JsonIgnore
    override fun getSuccessCriteria(): SuccessCriteria {
        val openAPI = types?.resolve()?.apiCoverage?.resolve()?.openAPI?.resolve()
        return openAPI?.successCriteria?.resolve() ?: SuccessCriteria.default
    }

    @JsonIgnore
    override fun excludedOpenAPIEndpoints(): List<String> {
        val openAPI = types?.resolve()?.apiCoverage?.resolve()?.openAPI?.resolve()
        return openAPI?.excludedEndpoints?.resolveFully() ?: emptyList()
    }
}

data class ReportTypes(
    @param:JsonProperty("APICoverage")
    val apiCoverage: TemplateOrValue<APICoverage>? = null
)

data class APICoverage(
    @param:JsonProperty("OpenAPI")
    val openAPI: TemplateOrValue<APICoverageConfiguration>? = null
)

data class APICoverageConfiguration(
    val successCriteria: TemplateOrValue<SuccessCriteria>? = null,
    val excludedEndpoints: TemplateOrValue<List<TemplateOrValue<String>>>? = null
)

data class SuccessCriteria(
    val minThresholdPercentage: TemplateOrValue<Int>? = null,
    val maxMissedEndpointsInSpec: TemplateOrValue<Int>? = null,
    val enforce: TemplateOrValue<Boolean>? = null
) {
    companion object {
        val default = SuccessCriteria(
            minThresholdPercentage = wrap(0),
            maxMissedEndpointsInSpec = wrap(0),
            enforce = wrap(false),
        )
    }

    @JsonIgnore
    fun getMinThresholdPercentageOrDefault(): Int {
        return minThresholdPercentage?.resolve() ?: 0
    }

    @JsonIgnore
    fun getMaxMissedEndpointsInSpecOrDefault(): Int {
        return maxMissedEndpointsInSpec?.resolve() ?: 0
    }

    @JsonIgnore
    fun getEnforceOrDefault(): Boolean {
        return enforce?.resolve() ?: false
    }
}

data class SecurityConfiguration(
    @param:JsonProperty("OpenAPI")
    val OpenAPI: TemplateOrValue<OpenAPISecurityConfiguration>?
) {
    val openAPI: OpenAPISecurityConfiguration?
        get() = OpenAPI?.resolve()

    fun getOpenAPISecurityScheme(scheme: String): SecuritySchemeConfiguration? {
        return openAPI?.securitySchemes?.get(scheme)?.resolve()
    }
}

data class OpenAPISecurityConfiguration(
    val securitySchemes: Map<String, TemplateOrValue<SecuritySchemeConfiguration>> = emptyMap()
)

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
    override val type: TemplateOrValue<String> = wrap("oauth2"),
    override val token: TemplateOrValue<String> = wrap("")
) : SecuritySchemeConfiguration(), SecuritySchemeWithOAuthToken

@JsonTypeName("basicAuth")
data class BasicAuthSecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = wrap("basicAuth"),
    val token: TemplateOrValue<String> = wrap("")
) : SecuritySchemeConfiguration()

@JsonTypeName("bearer")
data class BearerSecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = wrap("bearer"),
    override val token: TemplateOrValue<String> = wrap("")
) : SecuritySchemeConfiguration(), SecuritySchemeWithOAuthToken

@JsonTypeName("apiKey")
data class APIKeySecuritySchemeConfiguration(
    override val type: TemplateOrValue<String> = wrap("apiKey"),
    val value: TemplateOrValue<String> = wrap("")
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
