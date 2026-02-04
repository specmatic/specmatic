package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.conversions.SPECMATIC_BASIC_AUTH_TOKEN
import io.specmatic.conversions.SPECMATIC_OAUTH2_TOKEN
import io.specmatic.core.*
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.*
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_1
import io.specmatic.core.config.SpecmaticConfigVersion.VERSION_2
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
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_BASE_URL
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_ESCAPE_SOAP_ACTION
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_PRETTY_PRINT
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
import java.net.URI
import java.nio.file.Path

private const val TESTS_DIRECTORY_ENV_VAR = "SPECMATIC_TESTS_DIRECTORY"
private const val TESTS_DIRECTORY_PROPERTY = "specmaticTestsDirectory"
private const val CUSTOM_IMPLICIT_STUB_BASE_ENV_VAR = "SPECMATIC_CUSTOM_IMPLICIT_STUB_BASE"
private const val CUSTOM_IMPLICIT_STUB_BASE_PROPERTY = "customImplicitStubBase"
private const val TEST_ENDPOINTS_API = "endpointsAPI"
private const val TEST_FILTER_ENV_VAR = "filter"
private const val TEST_FILTER_PROPERTY = "filter"
private const val TEST_SWAGGER_UI_BASEURL_ENV_VAR = "swaggerUIBaseURL"
private const val TEST_SWAGGER_UI_BASEURL_PROPERTY = "swaggerUIBaseURL"
private const val TEST_BASE_URL_ENV_VAR = "testBaseURL"
private const val TEST_BASE_URL_PROPERTY = "testBaseURL"
private const val TEST_HOST_ENV_VAR = "host"
private const val TEST_HOST_PROPERTY = "host"
private const val TEST_PORT_ENV_VAR = "port"
private const val TEST_PORT_PROPERTY = "port"
private const val TEST_PROTOCOL_ENV_VAR = "protocol"
private const val TEST_PROTOCOL_PROPERTY = "protocol"
private const val TEST_FILTER_NAME_ENV_VAR = "FILTER_NAME"
private const val TEST_FILTER_NAME_PROPERTY = "filterName"
private const val TEST_FILTER_NOT_NAME_ENV_VAR = "FILTER_NOT_NAME"
private const val TEST_FILTER_NOT_NAME_PROPERTY = "filterNotName"
private const val TEST_OVERLAY_FILE_PATH_ENV_VAR = "overlayFilePath"
private const val TEST_OVERLAY_FILE_PATH_PROPERTY = "overlayFilePath"

fun invalidContractExtensionMessage(filename: String): String {
    return "The file $filename does not seem like a contract file. Valid extensions for contract files are ${CONTRACT_EXTENSIONS.joinToString(", ")}"
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
    private val defaultFields: List<String>? = null,
    @param:JsonAlias("query_param_key")
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

data class SpecmaticConfigV2Impl(
    val sources: List<Source> = emptyList(),
    private val auth: Auth? = null,
    val pipeline: Pipeline? = null,
    val environments: Map<String, Environment>? = null,
    private val hooks: Map<String, String> = emptyMap(),
    val proxy: ProxyConfig? = null,
    val repository: RepositoryInfo? = null,
    val report: ReportConfigurationDetails? = null,
    val security: SecurityConfiguration? = null,
    val test: TestConfiguration? = null,
    val stub: StubConfiguration? = null,
    val backwardCompatibility: BackwardCompatibilityConfig? = null,
    val virtualService: VirtualServiceConfiguration? = null,
    private val examples: List<String>? = null,
    val workflow: WorkflowConfiguration? = null,
    val ignoreInlineExamples: Boolean? = null,
    val ignoreInlineExampleWarnings: Boolean? = null,
    val schemaExampleDefault: Boolean? = null,
    val fuzzy: Boolean? = null,
    val extensibleQueryParams: Boolean? = null,
    val escapeSoapAction: Boolean? = null,
    val prettyPrint: Boolean? = null,
    val additionalExampleParamsFilePath: String? = null,
    val attributeSelectionPattern: AttributeSelectionPattern? = null,
    val allPatternsMandatory: Boolean? = null,
    val defaultPatternValues: Map<String, Any> = emptyMap(),
    private val version: SpecmaticConfigVersion? = VERSION_2,
    val disableTelemetry: Boolean? = null,
    val logging: LoggingConfiguration? = null,
    val mcp: McpConfiguration? = null,
    private val licensePath: Path? = null,
    val reportDirPath: Path? = null,
    val globalSettings: SpecmaticGlobalSettings? = null,
) : SpecmaticConfig {
    @JsonIgnore
    override fun getLogConfigurationOrDefault(): LoggingConfiguration {
        return this.logging ?: LoggingConfiguration.default()
    }

    @JsonIgnore
    override fun isTelemetryDisabled(): Boolean {
        val disableTelemetryFromEnvVarOrSystemProp = readEnvVarOrProperty(
            SPECMATIC_DISABLE_TELEMETRY, SPECMATIC_DISABLE_TELEMETRY
        )

        return disableTelemetryFromEnvVarOrSystemProp?.toBoolean()
            ?: (disableTelemetry == true)
    }

    override fun getTestConfiguration(): TestConfiguration? {
        return test
    }

    override fun getStubConfiguration(): StubConfiguration? {
        return stub
    }

    override fun getVirtualServiceConfiguration(): VirtualServiceConfiguration? {
        return virtualService
    }

    @JsonIgnore
    override fun testConfigFor(file: File, specType: SpecType): Map<String, Any> {
        val resolvedTestConfigs = this.sources.flatMap { it.getCanonicalTestConfigs() }
        val rawTestConfigs = this.sources.flatMap { it.test.orEmpty() }
        return resolvedTestConfigs.plus(rawTestConfigs).filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull { config ->
            if (config.specType != specType.value) return@firstOrNull false
            config.anyMatchesFile(file)
        }?.config.orEmpty()
    }

    @JsonIgnore
    override fun stubConfigFor(file: File, specType: SpecType): Map<String, Any> {
        val resolvedStubConfig = this.sources.flatMap { it.getCanonicalStubConfigs() }
        val rawStubConfigs = this.sources.flatMap { it.stub.orEmpty() }
        return resolvedStubConfig.plus(rawStubConfigs).filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull { config ->
            if (config.specType != specType.value) return@firstOrNull false
            config.anyMatchesFile(file)
        }?.config.orEmpty()
    }
    
    private fun SpecExecutionConfig.anyMatchesFile(file: File): Boolean {
        return this.specs().any { specPath ->
            val spec = Path.of(specPath).normalize()
            if (file.isAbsolute && spec.isAbsolute) return@any File(specPath).sameAs(file)
            if (!file.isAbsolute) return@any file.toPath().normalize().endsWith(spec)
            return@any false
        }
    }

    @JsonIgnore
    private fun List<SpecExecutionConfig>.configWith(specPath: String, specType: String): Map<String, Any> {
        return this.filterIsInstance<SpecExecutionConfig.ConfigValue>().firstOrNull {
            it.contains(specPath, specType)
        }?.config.orEmpty()
    }

    @JsonIgnore
    override fun getCtrfSpecConfig(absoluteSpecPath: String, testType: String, protocol: String, specType: String): CtrfSpecConfig {
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
    override fun getHotReload(): Switch? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getHotReload()
    }

    @JsonIgnore
    override fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfig {
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
    override fun getAttributeSelectionPatternDetails(): AttributeSelectionPatternDetails {
        return this.attributeSelectionPattern ?: AttributeSelectionPatternDetails.default
    }

    @JsonIgnore
    override fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings {
        return this.globalSettings ?: SpecmaticGlobalSettings()
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
    override fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
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
    override fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String {
        val parsedUrl = URI(url)
        return stubBaseUrls(defaultBaseUrl).map(::URI).firstOrNull { stubBaseUrl ->
            isSameBaseIgnoringHost(parsedUrl, stubBaseUrl)
        }?.path.orEmpty()
    }

    @JsonIgnore
    override fun getStubStartTimeoutInMilliseconds(): Long {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getStartTimeoutInMilliseconds() ?: 20_000L
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
    override fun loadSources(useCurrentBranchForCentralRepo: Boolean): List<ContractSource> {
        return sources.map { source ->
            val defaultBaseUrl = getDefaultBaseUrl()
            val stubExamplesMap = source.specToStubExamplesMap()
            val stubPaths = source.specToStubBaseUrlMap(defaultBaseUrl).entries.map { ContractSourceEntry(it.key, it.value, exampleDirPaths = stubExamplesMap[it.key]) }

            val testBaseUrlMap = source.specToTestBaseUrlMap(defaultBaseUrl)
            val testGenerativeMap = source.specToTestGenerativeMap()
            val testExamplesMap = source.specToTestExamplesMap()
            val testPaths = testBaseUrlMap.entries.map { ContractSourceEntry(it.key, it.value, testGenerativeMap[it.key], exampleDirPaths = testExamplesMap[it.key]) }

            val sourceMatchBranch = source.matchBranch ?: false
            val effectiveUseCurrentBranch = useCurrentBranchForCentralRepo || sourceMatchBranch
            val effectiveBranch = getEffectiveBranchForSource(source.branch, effectiveUseCurrentBranch)


            when (source.provider) {
                SourceProvider.git -> when (source.repository) {
                    null -> GitMonoRepo(testPaths, stubPaths, source.provider.toString())
                    else -> GitRepo(
                        source.repository,
                        effectiveBranch,
                        testPaths,
                        stubPaths,
                        source.provider.toString(),
                        effectiveUseCurrentBranch,
                        specmaticConfig = this
                    )
                }

                SourceProvider.filesystem -> LocalFileSystemSource(source.directory ?: ".", testPaths, stubPaths)

                SourceProvider.web -> WebSource(testPaths, stubPaths)
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
        return getAttributeSelectionPatternDetails().getQueryParamKey()
    }

    @JsonIgnore
    override fun isExtensibleSchemaEnabled(): Boolean {
        return test?.allowExtensibleSchema ?: getBooleanValue(EXTENSIBLE_SCHEMA)
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
        return test?.validateResponseValues ?: getBooleanValue(VALIDATE_RESPONSE_VALUE)
    }

    @JsonIgnore
    override fun parsedDefaultPatternValues(): Map<String, Value> {
        return parsedJSONObject(ObjectMapper().writeValueAsString(defaultPatternValues)).jsonObject
    }

    @JsonIgnore
    override fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        return (test?.resiliencyTests ?: ResiliencyTestsConfig.fromSystemProperties()).enable ?: ResiliencyTestSuite.none
    }

    @JsonIgnore
    override fun getTestTimeoutInMilliseconds(): Long? {
        return test?.timeoutInMilliseconds ?: getLongValue(SPECMATIC_TEST_TIMEOUT)
    }

    @JsonIgnore
    override fun getMaxTestRequestCombinations(): Int? {
        val configValue = if (getVersion() == VERSION_2) test?.maxTestRequestCombinations else null
        return configValue ?: getIntValue(MAX_TEST_REQUEST_COMBINATIONS)
    }

    @JsonIgnore
    override fun getTestStrictMode(): Boolean? {
        return test?.strictMode ?: getStringValue(TEST_STRICT_MODE)?.toBoolean()
    }

    @JsonIgnore
    override fun getTestLenientMode(): Boolean? {
        return test?.lenientMode ?: getStringValue(TEST_LENIENT_MODE)?.toBoolean()
    }

    @JsonIgnore
    override fun getTestParallelism(): String? {
        return test?.parallelism ?: getStringValue(SPECMATIC_TEST_PARALLELISM)
    }

    @JsonIgnore
    override fun getTestsDirectory(): String? {
        return test?.testsDirectory
            ?: readEnvVarOrProperty(TESTS_DIRECTORY_ENV_VAR, TESTS_DIRECTORY_PROPERTY)
    }

    @JsonIgnore
    override fun getMaxTestCount(): Int? {
        return test?.maxTestCount ?: getIntValue(MAX_TEST_COUNT)
    }

    @JsonIgnore
    override fun getTestFilter(): String? {
        val testConfig = this.test ?: TestConfiguration()
        return testConfig.filter
            ?: readEnvVarOrProperty(TEST_FILTER_ENV_VAR, TEST_FILTER_PROPERTY)
    }

    @JsonIgnore
    override fun getTestFilterName(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.filterName else null
        return configValue ?: readEnvVarOrProperty(TEST_FILTER_NAME_ENV_VAR, TEST_FILTER_NAME_PROPERTY)
    }

    @JsonIgnore
    override fun getTestFilterNotName(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.filterNotName else null
        return configValue ?: readEnvVarOrProperty(TEST_FILTER_NOT_NAME_ENV_VAR, TEST_FILTER_NOT_NAME_PROPERTY)
    }

    @JsonIgnore
    override fun getTestOverlayFilePath(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.overlayFilePath else null
        return configValue ?: readEnvVarOrProperty(TEST_OVERLAY_FILE_PATH_ENV_VAR, TEST_OVERLAY_FILE_PATH_PROPERTY)
    }

    @JsonIgnore
    override fun getTestBaseUrl(): String? {
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
    override fun getCoverageReportBaseUrl(): String? {
        val baseUrl = getExplicitTestBaseUrl()
        if (baseUrl != null) return baseUrl

        val host = readEnvVarOrProperty(TEST_HOST_ENV_VAR, TEST_HOST_PROPERTY).orEmpty()
        val port = readEnvVarOrProperty(TEST_PORT_ENV_VAR, TEST_PORT_PROPERTY).orEmpty()
        return if (host.isNotBlank() && port.isNotBlank()) "$host:$port" else null
    }

    @JsonIgnore
    private fun getExplicitTestBaseUrl(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.baseUrl else null
        return configValue ?: readEnvVarOrProperty(TEST_BASE_URL_ENV_VAR, TEST_BASE_URL_PROPERTY)
    }

    @JsonIgnore
    override fun getTestSwaggerUrl(): String? {
        val testConfig = this.test ?: TestConfiguration()
        return testConfig.swaggerUrl
    }

    @JsonIgnore
    override fun getTestSwaggerUIBaseUrl(): String? {
        val configValue = if (getVersion() == VERSION_2) test?.swaggerUIBaseURL else null
        return configValue ?: readEnvVarOrProperty(TEST_SWAGGER_UI_BASEURL_ENV_VAR, TEST_SWAGGER_UI_BASEURL_PROPERTY)
    }

    @JsonIgnore
    override fun getTestJunitReportDir(): String? {
        return if (getVersion() == VERSION_2) test?.junitReportDir else null
    }

    @JsonIgnore
    override fun getActuatorUrl(): String? {
        val testConfig = this.test ?: TestConfiguration()
        return testConfig.actuatorUrl ?: getStringValue(TEST_ENDPOINTS_API)
    }

    override fun getSecurityConfiguration(): SecurityConfiguration? {
        return this.security
    }

    @JsonIgnore
    override fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig {
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
    override fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getIncludeMandatoryAndRequestedKeysInResponse() ?: true
    }

    @JsonIgnore
    override fun getStubGenerative(): Boolean {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getGenerative() ?: false
    }

    @JsonIgnore
    override fun getStubDelayInMilliseconds(): Long? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getDelayInMilliseconds()
    }

    @JsonIgnore
    override fun getStubDictionary(): String? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getDictionary()
    }

    @JsonIgnore
    override fun getStubStrictMode(): Boolean? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getStrictMode()
    }

    @JsonIgnore
    override fun getStubFilter(): String? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getFilter()
    }

    @JsonIgnore
    override fun getStubHttpsConfiguration(): HttpsConfiguration? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getHttpsConfiguration()
    }

    @JsonIgnore
    override fun getStubGracefulRestartTimeoutInMilliseconds(): Long? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getGracefulRestartTimeoutInMilliseconds()
    }

    @JsonIgnore
    override fun getDefaultBaseUrl(): String {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getBaseUrl()
            ?: getStringValue(SPECMATIC_BASE_URL)
            ?: Configuration.Companion.DEFAULT_BASE_URL
    }

    @JsonIgnore
    override fun getCustomImplicitStubBase(): String? {
        val stubConfig = this.stub ?: StubConfiguration()
        return stubConfig.getCustomImplicitStubBase()
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

    override fun getWorkflowConfiguration(): WorkflowConfiguration? {
        return workflow
    }

    @JsonIgnore
    override fun getAdditionalExampleParamsFilePath(): File? {
        return (additionalExampleParamsFilePath ?: getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE))?.let(::File)
    }

    override fun getHooks(): Map<String, String> {
        return hooks
    }

    @JsonIgnore
    override fun getProxyConfig(): ProxyConfig? {
        return proxy
    }

    override fun getVersion(): SpecmaticConfigVersion {
        return this.version ?: VERSION_1
    }

    @JsonIgnore
    override fun getMatchBranchEnabled(): Boolean {
        return sources.any { it.matchBranch == true } || getBooleanValue(Flags.MATCH_BRANCH)
    }

    @JsonIgnore
    fun mapSources(transform: (Source) -> Source): SpecmaticConfig {
        val transformedSources = this.sources.map(transform)
        return this.copy(sources = transformedSources)
    }

    override fun getAuth(): Auth? {
        return auth
    }

    @JsonIgnore
    override fun getAuthBearerFile(): String? {
        return auth?.bearerFile
    }

    @JsonIgnore
    override fun getAuthBearerEnvironmentVariable(): String? {
        return auth?.bearerEnvironmentVariable
    }

    @JsonIgnore
    override fun getAuthPersonalAccessToken(): String? {
        val tokenFromConfig = auth?.personalAccessToken?.takeIf { it.isNotBlank() }
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

    override fun getExamples(): List<String> {
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
    override fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig? {
        return this.backwardCompatibility
    }

    @JsonIgnore
    override fun getMcpConfiguration(): McpConfiguration? {
        return this.mcp
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
    override fun getOpenAPISecurityConfigurationScheme(scheme: String): SecuritySchemeConfiguration? {
        return security?.getOpenAPISecurityScheme(scheme)
    }

    @JsonIgnore
    override fun getBasicAuthSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? BasicAuthSecuritySchemeConfiguration)?.token
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_BASIC_AUTH_TOKEN)
    }

    @JsonIgnore
    override fun getBearerSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? SecuritySchemeWithOAuthToken)?.token
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_OAUTH2_TOKEN)
    }

    @JsonIgnore
    override fun getApiKeySecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? APIKeySecuritySchemeConfiguration)?.value
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
    fun getVirtualServiceHost(): String? {
        return this.virtualService?.getHost()
    }

    @JsonIgnore
    fun getVirtualServicePort(): Int? {
        return this.virtualService?.getPort()
    }

    @JsonIgnore
    fun getVirtualServiceSpecs(): List<String>? {
        return this.virtualService?.getSpecs()
    }

    @JsonIgnore
    fun getVirtualServiceSpecsDirPath(): String? {
        return this.virtualService?.getSpecsDirPath()
    }

    @JsonIgnore
    fun getVirtualServiceLogsDirPath(): String? {
        return this.virtualService?.getLogsDirPath()
    }

    @JsonIgnore
    fun getVirtualServiceLogMode(): VirtualServiceConfiguration.VSLogMode? {
        return this.virtualService?.getLogMode()
    }

    @JsonIgnore
    fun getVirtualServiceNonPatchableKeys(): Set<String> {
        return this.virtualService?.getNonPatchableKeys().orEmpty()
    }

    @JsonIgnore
    fun stubContracts(relativeTo: File = File(".")): List<String> {
        return sources.flatMap { source ->
            source.stub.orEmpty().flatMap { stub ->
                stub.specs()
            }.map { spec ->
                if (source.provider == SourceProvider.web) spec
                else spec.canonicalPath(relativeTo)
            }
        }
    }

    override fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig {
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

    override fun enableResiliencyTests(): SpecmaticConfig {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                resiliencyTests = (testConfig.resiliencyTests ?: ResiliencyTestsConfig()).copy(
                    enable = ResiliencyTestSuite.all,
                ),
            ),
        )
    }

    override fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfig {
        val testConfig = test ?: TestConfiguration()
        return this.copy(
            test = testConfig.copy(
                strictMode = strictMode ?: testConfig.strictMode,
                lenientMode = lenientMode,
            ),
        )
    }

    override fun withExamples(exampleDirectories: List<String>): SpecmaticConfig {
        return return this.copy(examples = exampleDirectories)
    }

    override fun withUseCurrentBranchForCentralRepo(useCurrentBranchForCentralRepo: Boolean): SpecmaticConfig {
        val updatedSources = this.sources.map { it.copy(matchBranch = useCurrentBranchForCentralRepo) }
        return this.copy(sources = updatedSources)
    }

    override fun withTestFilter(filter: String): SpecmaticConfig {
        val testConfig = this.test ?: TestConfiguration()
        return this.copy(test = testConfig.copy(filter = filter))
    }

    override fun withTestTimeout(timeoutInMilliSeconds: Long): SpecmaticConfig {
        val testConfig = this.test ?: TestConfiguration()
        return this.copy(test = testConfig.copy(timeoutInMilliseconds = timeoutInMilliSeconds))
    }

    override fun withStubModes(strictMode: Boolean): SpecmaticConfig {
        val stubConfig = this.stub ?: StubConfiguration()
        return this.copy(stub = stubConfig.copy(strictMode = strictMode))
    }

    override fun withStubFilter(filter: String): SpecmaticConfig {
        val stubConfig = this.stub ?: StubConfiguration()
        return this.copy(stub = stubConfig.copy(filter = filter))
    }

    override fun withGlobalMockDelay(delay: Long): SpecmaticConfig {
        val stubConfig = this.stub ?: StubConfiguration()
        return this.copy(stub = stubConfig.copy(delayInMilliseconds = delay))
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

    override fun getLicensePath(): Path? {
        return licensePath
    }

    @JsonIgnore
    override fun getReportDirPath(suffix: String?): Path {
        val reportDirPath = reportDirPath ?: defaultReportDirPath
        return if (suffix == null) reportDirPath
        else reportDirPath.resolve(suffix)
    }

    private fun File.sameAs(other: File): Boolean {
        return try {
            this.toPath().toRealPath() == other.toPath().toRealPath()
        } catch (_: Exception) {
            this.canonicalFile == other.canonicalFile
        }
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
    val provider: SourceProvider = SourceProvider.filesystem,
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
        return stub.orEmpty().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl) { configValue ->
                if(configValue.specType == SpecType.OPENAPI.value) OpenAPIMockConfig.from(configValue.config).baseUrl
                else null
            }
        }.toMap()
    }

    fun specToTestBaseUrlMap(defaultBaseUrl: String? = null): Map<String, String?> {
        val baseUrlFromConfig : (SpecExecutionConfig.ConfigValue) -> String? = {
            if(it.specType == SpecType.OPENAPI.value) OpenAPITestConfig.from(it.config).baseUrl
            else null
        }
        return test?.flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl, baseUrlFromConfig)
        }?.toMap() ?: test.orEmpty().flatMap {
            it.specToBaseUrlPairList(defaultBaseUrl, baseUrlFromConfig)
        }.toMap()
    }

    fun specToTestGenerativeMap(): Map<String, ResiliencyTestSuite?> {
        return test?.flatMap {
            when (it) {
                is SpecExecutionConfig.StringValue -> listOf(it.value to null)
                is SpecExecutionConfig.ObjectValue -> it.specs.map { specPath ->
                    specPath to it.resiliencyTests?.enable
                }
                is SpecExecutionConfig.ConfigValue  -> it.specs.map { specPath ->
                    if(it.specType == SpecType.OPENAPI.value) specPath to OpenAPITestConfig.from(it.config).resiliencyTests?.enable
                    else specPath to null
                }
            }
        }?.toMap() ?: emptyMap()
    }

    fun specToTestExamplesMap(): Map<String, List<String>> {
        return test?.flatMap {
            if(it is SpecExecutionConfig.ConfigValue && it.specType == SpecType.OPENAPI.value) {
                return@flatMap it.specs.map { specPath ->
                    specPath to OpenAPITestConfig.from(it.config).examples.orEmpty()
                }
            }
            emptyList()
        }?.toMap().orEmpty()
    }

    fun specToStubExamplesMap(): Map<String, List<String>> {
        return stub?.flatMap {
            if(it is SpecExecutionConfig.ConfigValue && it.specType == SpecType.OPENAPI.value) {
                return@flatMap it.specs.map { specPath ->
                    specPath to OpenAPIMockConfig.from(it.config).examples.orEmpty()
                }
            }
            emptyList()
        }?.toMap().orEmpty()
    }

    fun getCanonicalTestConfigs(): List<SpecExecutionConfig> {
        if (this.test == null) return emptyList()
        val baseDirectory = getBaseDirectory()
        return this.test.map { config ->  config.resolveAgainst(baseDirectory) }
    }

    fun getCanonicalStubConfigs(): List<SpecExecutionConfig> {
        if (this.stub == null) return emptyList()
        val baseDirectory = getBaseDirectory()
        return this.stub.map { config ->  config.resolveAgainst(baseDirectory) }
    }

    private fun getBaseDirectory(): File {
        val currentWorkingDirectory = File(".").canonicalFile
        return when (provider) {
            SourceProvider.web -> currentWorkingDirectory
            SourceProvider.filesystem -> directory?.let(::File) ?: currentWorkingDirectory
            SourceProvider.git -> {
                val repository = repository?.split("/")?.lastOrNull()?.removeSuffix(".git")
                if (repository != null) {
                    File(WorkingDirectory().path).resolve("repos").resolve(repository)
                } else {
                    currentWorkingDirectory
                }
            }
        }
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

fun readEnvVarOrProperty(
    envVarName: String,
    propertyName: String,
): String? = System.getenv(envVarName) ?: System.getProperty(propertyName)
