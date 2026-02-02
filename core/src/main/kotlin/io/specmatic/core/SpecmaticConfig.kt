package io.specmatic.core

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.DatabindException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.*
import io.specmatic.core.Configuration.Companion.configFilePath
import io.specmatic.core.SourceProvider.*
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_1
import io.specmatic.core.config.Switch
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.config.v3.ConsumesDeserializer
import io.specmatic.core.config.v3.SpecExecutionConfig
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.utilities.*
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
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.stub.isSameBaseIgnoringHost
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import java.io.File
import java.net.URI
import java.nio.file.Path

private const val excludedEndpointsWarning =
    "WARNING: excludedEndpoints is not supported in Specmatic config v2. . Refer to https://specmatic.io/documentation/configuration.html#report-configuration to see how to exclude endpoints."

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
    private val generative: Boolean? = null,
    private val delayInMilliseconds: Long? = null,
    private val dictionary: String? = null,
    private val includeMandatoryAndRequestedKeysInResponse: Boolean? = null,
    private val startTimeoutInMilliseconds: Long? = null,
    private val hotReload: Switch? = null,
    private val strictMode: Boolean? = null
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

    fun getStartTimeoutInMilliseconds(): Long? {
        return startTimeoutInMilliseconds
    }

    fun getHotReload(): Switch? {
        return hotReload
    }

    fun getStrictMode(): Boolean? {
        return strictMode ?: getBooleanValue(Flags.STUB_STRICT_MODE, false)
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
    private val virtualService: VirtualServiceConfiguration? = null,
    private val examples: List<String>? = null,
    private val workflow: WorkflowConfiguration? = null,
    private val ignoreInlineExamples: Boolean? = null,
    private val additionalExampleParamsFilePath: String? = null,
    private val attributeSelectionPattern: AttributeSelectionPattern? = null,
    private val allPatternsMandatory: Boolean? = null,
    private val defaultPatternValues: Map<String, Any> = emptyMap(),
    private val matchBranch: Boolean? = null,
    private val version: SpecmaticConfigVersion? = null,
    private val disableTelemetry: Boolean? = null,
    private val licensePath: Path? = null,
    private val reportDirPath: Path? = null,
) {
    companion object {
        fun getReport(specmaticConfig: SpecmaticConfig): ReportConfigurationDetails? {
            return specmaticConfig.report
        }

        @JsonIgnore
        fun getSources(specmaticConfig: SpecmaticConfig): List<Source> {
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
            return specmaticConfig.virtualService ?: VirtualServiceConfiguration()
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
            return specmaticConfig.attributeSelectionPattern ?: AttributeSelectionPattern()
        }

        @JsonIgnore
        fun getStubConfiguration(specmaticConfig: SpecmaticConfig): StubConfiguration {
            return specmaticConfig.stub ?: StubConfiguration()
        }

        @JsonIgnore
        fun getTestConfigOrNull(specmaticConfig: SpecmaticConfig): TestConfiguration? {
            return specmaticConfig.test
        }

        @JsonIgnore
        fun getStubConfigOrNull(specmaticConfig: SpecmaticConfig): StubConfiguration? {
            return specmaticConfig.stub
        }

        @JsonIgnore
        fun getVirtualServiceConfigOrNull(specmaticConfig: SpecmaticConfig): VirtualServiceConfiguration? {
            return specmaticConfig.virtualService
        }

        @JsonIgnore
        fun getAttributeSelectionConfigOrNull(specmaticConfig: SpecmaticConfig): AttributeSelectionPattern? {
            return specmaticConfig.attributeSelectionPattern
        }

        fun getEnvironments(specmaticConfig: SpecmaticConfig): Map<String, Environment>? {
            return specmaticConfig.environments
        }

        @JsonIgnore
        fun getHooks(specmaticConfig: SpecmaticConfig): Map<String, String> {
            return specmaticConfig.hooks
        }

        @JsonIgnore
        fun getProxyConfig(specmaticConfig: SpecmaticConfig): ProxyConfig? {
            return specmaticConfig.proxy
        }
    }

    @JsonIgnore
    fun isTelemetryDisabled(): Boolean {
        val disableTelemetryFromEnvVarOrSystemProp = readEnvVarOrProperty(
            SPECMATIC_DISABLE_TELEMETRY, SPECMATIC_DISABLE_TELEMETRY
        )

        return disableTelemetryFromEnvVarOrSystemProp?.toBoolean()
            ?: (disableTelemetry == true)
    }

    @JsonIgnore
    fun testConfigFor(specPath: String, specType: String): Map<String, Any> {
        return sources.flatMap { it.test.orEmpty() }.configWith(specPath, specType)
    }

    @JsonIgnore
    fun stubConfigFor(specPath: String, specType: String): Map<String, Any> {
        return sources.flatMap { it.stub.orEmpty() }.configWith(specPath, specType)
    }

    @JsonIgnore
    private fun List<SpecExecutionConfig>.configWith(specPath: String, specType: String): Map<String, Any> {
        return this.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull {
            it.contains(specPath, specType)
        }?.config.orEmpty()
    }

    @JsonIgnore
    fun getCtrfSpecConfig(absoluteSpecPath: String, testType: String, protocol: String, specType: String): CtrfSpecConfig {
        val source = when (testType) {
            CONTRACT_TEST_TEST_TYPE -> testSourceFromConfig(absoluteSpecPath)
            else -> stubSourceFromConfig(absoluteSpecPath)
        } ?: Source()

        val specPathFromConfig = when(testType) {
            CONTRACT_TEST_TEST_TYPE -> testSpecPathFromConfigFor(absoluteSpecPath)
            else -> stubSpecPathFromConfigFor(absoluteSpecPath)
        }

        return CtrfSpecConfig(
            protocol = protocol,
            specType = specType,
            specification = specPathFromConfig.orEmpty(),
            sourceProvider = source.provider.name,
            repository = source.repository.orEmpty(),
            branch = source.branch ?: "main",
        )
    }

    @JsonIgnore
    fun getHotReload(): Switch? {
        return getStubConfiguration(this).getHotReload()
    }

    @JsonIgnore
    fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfig {
        if (latestVersion == VERSION_1)
            return this

        logger.log("\n$excludedEndpointsWarning\n")

        return this.copy(
            report = report?.clearPresenceOfExcludedEndpoints()
        )
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
        return getAttributeSelectionPattern(this)
    }

    @JsonIgnore
    fun stubBaseUrls(defaultBaseUrl: String): List<String> =
        sources
            .flatMap { source ->
                source.stub.orEmpty().map { consumes ->
                    baseUrlFrom(consumes, defaultBaseUrl)
                }
            }.distinct()

    @JsonIgnore
    fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
        return sources.flatMap { source ->
            source.stub.orEmpty().flatMap { consumes ->
                when (consumes) {
                    is SpecExecutionConfig.StringValue -> listOf(consumes.value to defaultBaseUrl)
                    is SpecExecutionConfig.ObjectValue -> consumes.specs.map { it to consumes.toBaseUrl(defaultBaseUrl) }
                    is SpecExecutionConfig.ConfigValue -> consumes.specs.map { it to defaultBaseUrl }
                }
            }
        }
    }

    @JsonIgnore
    fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String {
        val parsedUrl = URI(url)
        return stubBaseUrls(defaultBaseUrl).map(::URI).firstOrNull { stubBaseUrl ->
            isSameBaseIgnoringHost(parsedUrl, stubBaseUrl)
        }?.path.orEmpty()
    }

    @JsonIgnore
    fun getStubStartTimeoutInMilliseconds(): Long {
        return getStubConfiguration(this).getStartTimeoutInMilliseconds() ?: 20_000L
    }

    @JsonIgnore
    private fun baseUrlFrom(
        consumes: SpecExecutionConfig,
        defaultBaseUrl: String
    ): String = if (consumes is SpecExecutionConfig.ObjectValue) consumes.toBaseUrl(defaultBaseUrl) else defaultBaseUrl

    fun logDependencyProjects(azure: AzureAPI) {
        logger.log("Dependency projects")
        logger.log("-------------------")

        sources.forEach { source ->
            logger.log("In central repo ${source.repository}")

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


    @JsonIgnore
    fun loadSources(useCurrentBranchForCentralRepo: Boolean = false): List<ContractSource> {
        return sources.map { source ->
            val stubPaths = source.specToStubBaseUrlMap().entries.map { ContractSourceEntry(it.key, it.value) }
            val testBaseUrlMap = source.specToTestBaseUrlMap()
            val testGenerativeMap = source.specToTestGenerativeMap()
            val testPaths = testBaseUrlMap.entries.map { ContractSourceEntry(it.key, it.value, testGenerativeMap[it.key]) }

            val sourceMatchBranch = source.matchBranch ?: false
            val effectiveUseCurrentBranch = useCurrentBranchForCentralRepo || sourceMatchBranch
            val effectiveBranch = getEffectiveBranchForSource(source.branch, effectiveUseCurrentBranch)

            when (source.provider) {
                git -> when (source.repository) {
                    null -> GitMonoRepo(testPaths, stubPaths, source.provider.toString())
                    else -> GitRepo(source.repository, effectiveBranch, testPaths, stubPaths, source.provider.toString(), effectiveUseCurrentBranch)
                }

                filesystem -> LocalFileSystemSource(source.directory ?: ".", testPaths, stubPaths)

                web -> WebSource(testPaths, stubPaths)
            }
        }
    }

    private fun getEffectiveBranchForSource(
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

    @JsonIgnore
    fun attributeSelectionQueryParamKey(): String {
        return getAttributeSelectionPattern().getQueryParamKey()
    }

    @JsonIgnore
    fun isExtensibleSchemaEnabled(): Boolean {
        return test?.allowExtensibleSchema ?: getBooleanValue(EXTENSIBLE_SCHEMA)
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
        return test?.validateResponseValues ?: getBooleanValue(VALIDATE_RESPONSE_VALUE)
    }

    @JsonIgnore
    fun parsedDefaultPatternValues(): Map<String, Value> {
        return parsedJSONObject(ObjectMapper().writeValueAsString(defaultPatternValues)).jsonObject
    }

    @JsonIgnore
    fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        return (test?.resiliencyTests ?: ResiliencyTestsConfig.fromSystemProperties()).enable ?: ResiliencyTestSuite.none
    }

    @JsonIgnore
    fun getTestTimeoutInMilliseconds(): Long? {
        return test?.timeoutInMilliseconds ?: getLongValue(SPECMATIC_TEST_TIMEOUT)
    }

    @JsonIgnore
    fun getTestStrictMode(): Boolean? {
        return test?.strictMode
    }

    @JsonIgnore
    fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                resiliencyTests = (testConfig.resiliencyTests ?: ResiliencyTestsConfig.fromSystemProperties()).copy(
                    enable = if (onlyPositive) ResiliencyTestSuite.positiveOnly else ResiliencyTestSuite.all
                )
            )
        )
    }

    @JsonIgnore
    fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        return getStubConfiguration(this).getIncludeMandatoryAndRequestedKeysInResponse() ?: true
    }

    @JsonIgnore
    fun getStubGenerative(): Boolean {
        return getStubConfiguration(this).getGenerative() ?: false
    }

    @JsonIgnore
    fun getStubDelayInMilliseconds(): Long? {
        return getStubConfiguration(this).getDelayInMilliseconds()
    }

    @JsonIgnore
    fun getStubDictionary(): String? {
        return getStubConfiguration(this).getDictionary()
    }

    @JsonIgnore
    fun getStubStrictMode(): Boolean? {
        return getStubConfiguration(this).getStrictMode()
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
    fun getProxyConfig(): ProxyConfig? {
        return proxy
    }

    @JsonIgnore
    fun getDefaultPatternValues(): Map<String, Any> {
        return defaultPatternValues
    }

    fun getVersion(): SpecmaticConfigVersion {
        return this.version ?: VERSION_1
    }

    @JsonIgnore
    fun getMatchBranch(): Boolean? {
        return matchBranch
    }


    @JsonIgnore
    fun getAuth(): Auth? {
        return auth
    }

    @JsonIgnore
    fun getAuthBearerFile(): String? {
        return auth?.bearerFile
    }

    @JsonIgnore
    fun getAuthBearerEnvironmentVariable(): String? {
        return auth?.bearerEnvironmentVariable
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
        return getVirtualServiceConfiguration(this).getNonPatchableKeys()
    }

    @JsonIgnore
    fun stubContracts(relativeTo: File = File(".")): List<String> {
        return sources.flatMap { source ->
            source.stub.orEmpty().flatMap { stub ->
                stub.specs()
            }.map { spec ->
                if (source.provider == web) spec
                else spec.canonicalPath(relativeTo)
            }
        }
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

    fun enableResiliencyTests(): SpecmaticConfig {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                resiliencyTests = (testConfig.resiliencyTests ?: ResiliencyTestsConfig()).copy(
                    enable = ResiliencyTestSuite.all,
                ),
            ),
        )
    }

    @JsonIgnore
    fun testSpecPathFromConfigFor(absoluteSpecPath: String): String? {
        val source = testSourceFromConfig(absoluteSpecPath) ?: return null
        return source.specsUsedAsTest().firstOrNull {
            absoluteSpecPath.contains(it)
        }
    }

    @JsonIgnore
    fun stubSpecPathFromConfigFor(absoluteSpecPath: String): String? {
        val source = stubSourceFromConfig(absoluteSpecPath) ?: return null
        return source.specsUsedAsStub().firstOrNull {
            absoluteSpecPath.contains(it)
        }
    }

    @JsonIgnore
    private fun testSourceFromConfig(absoluteSpecPath: String): Source? {
        return sources.firstOrNull { source ->
            source.test.orEmpty().any { test -> test.contains(absoluteSpecPath) }
        }
    }

    @JsonIgnore
    private fun stubSourceFromConfig(absoluteSpecPath: String): Source? {
        return sources.firstOrNull { source ->
            source.stub.orEmpty().any { stub -> stub.contains(absoluteSpecPath) }
        }
    }

    @JsonIgnore
    private fun String.canonicalPath(relativeTo: File): String {
        return relativeTo.parentFile?.resolve(this)?.canonicalPath ?: File(this).canonicalPath
    }

    @JsonIgnore
    fun getLicensePath(): Path? {
        return licensePath
    }

    @JsonIgnore
    fun getReportDirPath(suffix: String? = null): Path {
        if(reportDirPath == null) return defaultReportDirPath
        if(suffix == null) return reportDirPath
        return reportDirPath.resolve(suffix)
    }
}

data class TestConfiguration(
    val resiliencyTests: ResiliencyTestsConfig? = null,
    val validateResponseValues: Boolean? = null,
    val allowExtensibleSchema: Boolean? = null,
    val timeoutInMilliseconds: Long? = null,
    val strictMode: Boolean? = null
)

enum class ResiliencyTestSuite {
    all, positiveOnly, none
}

data class ResiliencyTestsConfig(
    val enable: ResiliencyTestSuite? = null
) {
    constructor(isResiliencyTestFlagEnabled: Boolean, isOnlyPositiveFlagEnabled: Boolean) : this(
        enable = getEnableFrom(isResiliencyTestFlagEnabled, isOnlyPositiveFlagEnabled)
    )

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
    @param:JsonProperty("bearer-file") val bearerFile: String? = null,
    @param:JsonProperty("bearer-environment-variable") val bearerEnvironmentVariable: String? = null
)

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
    @JsonDeserialize(using = ConsumesDeserializer::class)
    val test: List<SpecExecutionConfig>? = null,
    @field:JsonAlias("consumes")
    @JsonDeserialize(using = ConsumesDeserializer::class)
    val stub: List<SpecExecutionConfig>? = null,
    val directory: String? = null,
    val matchBranch: Boolean? = null,
) {
    constructor(test: List<String>? = null, stub: List<String>? = null) : this(
        test = test?.map { SpecExecutionConfig.StringValue(it) },
        stub = stub?.map { SpecExecutionConfig.StringValue(it) }
    )

    fun specsUsedAsStub(): List<String> {
        return stub.orEmpty().flatMap { it.specs() }
    }

    fun specsUsedAsTest(): List<String> {
        return test?.flatMap { it.specs() } ?: test.orEmpty().flatMap { it.specs() }
    }

    fun specToStubBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        return stub.orEmpty().flatMap { it.specToBaseUrlPairList(defaultBaseUrl) }.toMap()
    }

    fun specToTestBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        return test?.flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl)
        }?.toMap() ?: test.orEmpty().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl)
        }.toMap()
    }

    fun specToTestGenerativeMap(): Map<String, ResiliencyTestSuite?> {
        return test?.flatMap {
            when (it) {
                is SpecExecutionConfig.StringValue -> listOf(it.value to null)
                is SpecExecutionConfig.ObjectValue -> it.specs.map { specPath ->
                    specPath to it.resiliencyTests?.enable
                }
                is SpecExecutionConfig.ConfigValue -> it.specs.map { specPath ->
                    specPath to null
                }
            }
        }?.toMap() ?: emptyMap()
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
    fun getSuccessCriteria(): SuccessCriteria
    fun excludedOpenAPIEndpoints(): List<String>

    companion object {
        val default = ReportConfigurationDetails(
           types = ReportTypes()
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportConfigurationDetails(
    val types: ReportTypes? = null
) : ReportConfiguration {

    fun validatePresenceOfExcludedEndpoints(currentVersion: SpecmaticConfigVersion): ReportConfigurationDetails {
        if(currentVersion.isLessThanOrEqualTo(VERSION_1))
            return this

        if (types?.apiCoverage?.openAPI?.excludedEndpoints.orEmpty().isNotEmpty()) {
            throw UnsupportedOperationException(excludedEndpointsWarning)
        }
        return this
    }

    fun clearPresenceOfExcludedEndpoints(): ReportConfigurationDetails {
        return this.copy(
            types = types?.copy(
                apiCoverage = types.apiCoverage?.copy(
                    openAPI = types.apiCoverage.openAPI?.copy(
                        excludedEndpoints = emptyList()
                    )
                )
            )
        )
    }

    @JsonIgnore
    override fun getSuccessCriteria(): SuccessCriteria {
        return types?.apiCoverage?.openAPI?.successCriteria ?: SuccessCriteria.default
    }

    @JsonIgnore
    override fun excludedOpenAPIEndpoints(): List<String> {
        return types?.apiCoverage?.openAPI?.excludedEndpoints ?: emptyList()
    }
}

data class ReportTypes(
    @param:JsonProperty("APICoverage")
    val apiCoverage: APICoverage? = null
)

data class APICoverage(
    @param:JsonProperty("OpenAPI")
    val openAPI: APICoverageConfiguration? = null
)

data class APICoverageConfiguration(
    val successCriteria: SuccessCriteria? = null,
    val excludedEndpoints: List<String>? = null
)

data class SuccessCriteria(
    val minThresholdPercentage: Int? = null,
    val maxMissedEndpointsInSpec: Int? = null,
    val enforce: Boolean? = null
) {
    companion object {
        val default = SuccessCriteria(0, 0, false)
    }

    @JsonIgnore
    fun getMinThresholdPercentageOrDefault(): Int {
        return minThresholdPercentage ?: 0
    }

    @JsonIgnore
    fun getMaxMissedEndpointsInSpecOrDefault(): Int {
        return maxMissedEndpointsInSpec ?: 0
    }

    @JsonIgnore
    fun getEnforceOrDefault(): Boolean {
        return enforce ?: false
    }
}

data class SecurityConfiguration(
    @param:JsonProperty("OpenAPI")
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
    return loadSpecmaticConfigOrNull(configFileName) ?: SpecmaticConfig()
}

fun loadSpecmaticConfigOrNull(configFileName: String? = null): SpecmaticConfig? =
    loadSpecmaticConfigOrNull(configFileName, explicitlySpecifiedByUser = false)

fun loadSpecmaticConfigOrNull(
    configFileName: String? = null,
    explicitlySpecifiedByUser: Boolean = false
): SpecmaticConfig? {
    return if (configFileName == null) {
        SpecmaticConfig()
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
