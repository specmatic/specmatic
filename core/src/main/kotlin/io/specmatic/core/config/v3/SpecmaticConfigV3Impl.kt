package io.specmatic.core.config.v3

import io.specmatic.conversions.SPECMATIC_BASIC_AUTH_TOKEN
import io.specmatic.conversions.SPECMATIC_OAUTH2_TOKEN
import io.specmatic.core.APIKeySecuritySchemeConfiguration
import io.specmatic.core.AttributeSelectionPattern
import io.specmatic.core.AttributeSelectionPatternDetails
import io.specmatic.core.Auth
import io.specmatic.core.BasicAuthSecuritySchemeConfiguration
import io.specmatic.core.CUSTOM_IMPLICIT_STUB_BASE_ENV_VAR
import io.specmatic.core.CUSTOM_IMPLICIT_STUB_BASE_PROPERTY
import io.specmatic.core.CertRegistry
import io.specmatic.core.Configuration
import io.specmatic.core.OpenAPISecurityConfiguration
import io.specmatic.core.PipelineProvider
import io.specmatic.core.ProxyConfig
import io.specmatic.core.ReportConfiguration
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.ResiliencyTestsConfig
import io.specmatic.core.SPECMATIC_DISABLE_TELEMETRY
import io.specmatic.core.SPECMATIC_STUB_DICTIONARY
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.SecuritySchemeConfiguration
import io.specmatic.core.SecuritySchemeWithOAuthToken
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecificationSource
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getEffectiveBranchForSource
import io.specmatic.core.TESTS_DIRECTORY_ENV_VAR
import io.specmatic.core.TESTS_DIRECTORY_PROPERTY
import io.specmatic.core.TEST_BASE_URL_ENV_VAR
import io.specmatic.core.TEST_BASE_URL_PROPERTY
import io.specmatic.core.TEST_FILTER_ENV_VAR
import io.specmatic.core.TEST_FILTER_NAME_ENV_VAR
import io.specmatic.core.TEST_FILTER_NAME_PROPERTY
import io.specmatic.core.TEST_FILTER_NOT_NAME_ENV_VAR
import io.specmatic.core.TEST_FILTER_NOT_NAME_PROPERTY
import io.specmatic.core.TEST_FILTER_PROPERTY
import io.specmatic.core.TEST_HOST_ENV_VAR
import io.specmatic.core.TEST_HOST_PROPERTY
import io.specmatic.core.TEST_PORT_ENV_VAR
import io.specmatic.core.TEST_PORT_PROPERTY
import io.specmatic.core.VirtualServiceConfiguration
import io.specmatic.core.WorkflowDetails
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.SpecmaticSpecConfig
import io.specmatic.core.config.Switch
import io.specmatic.core.config.v3.components.runOptions.AsyncApiRunOptions
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.runOptions.IRunOptionSpecification
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.OpenApiRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.ProtobufRunOptions
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.GitAuthentication
import io.specmatic.core.config.v3.specmatic.Governance
import io.specmatic.core.config.v3.specmatic.SuccessCriterion
import io.specmatic.core.defaultReportDirPath
import io.specmatic.core.readEnvVarOrProperty
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_QUERY_PARAMS
import io.specmatic.core.utilities.Flags.Companion.MAX_TEST_COUNT
import io.specmatic.core.utilities.Flags.Companion.MAX_TEST_REQUEST_COMBINATIONS
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_BASE_URL
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_ESCAPE_SOAP_ACTION
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
import io.specmatic.core.utilities.GitMonoRepo
import io.specmatic.core.utilities.GitRepo
import io.specmatic.core.utilities.LocalFileSystemSource
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.TestResultRecord.Companion.CONTRACT_TEST_TEST_TYPE
import java.io.File
import java.nio.file.Path
import kotlin.collections.mapNotNull
import kotlin.collections.orEmpty

data class SpecmaticConfigV3Impl(val file: File? = null, private val specmaticConfig: SpecmaticConfigV3) : SpecmaticConfig {
    private val effectiveWorkingFile: File = file?.canonicalFile ?: File(".").canonicalFile
    private val resolver = SpecmaticConfigV3Resolver(specmaticConfig.components ?: Components(), effectiveWorkingFile.toPath())

    private val specmaticSettings: ConcreteSettings by lazy(LazyThreadSafetyMode.NONE) {
        val settingsOrRef = specmaticConfig.specmatic?.settings ?: return@lazy ConcreteSettings()
        settingsOrRef.resolveElseThrow(resolver)
    }

    private val testSettings: TestSettings by lazy(LazyThreadSafetyMode.NONE) {
        val globalTestSettings = specmaticConfig.specmatic?.settings?.resolveElseThrow(resolver)?.test
        specmaticConfig.systemUnderTest?.getSettings(globalTestSettings, resolver) ?: globalTestSettings ?: TestSettings()
    }

    private fun getMockService(specFile: File): CommonServiceConfig<MockRunOptions, MockSettings>? {
        return specmaticConfig.dependencies?.getService(specFile, resolver)
    }

    private fun getMockRunOptions(specFile: File, @Suppress("SameParameterValue") specType: SpecType): IRunOptions? {
        val service = getMockService(specFile) ?: return null
        return specmaticConfig.dependencies?.getRunOptions(service, resolver, specType)
    }

    private fun getMockRunOptionsSpecDefinition(specFile: File, specType: SpecType): IRunOptionSpecification? {
        val service = getMockService(specFile) ?: return null
        val specificationId = specmaticConfig.dependencies?.getSpecDefinitionFor(specFile, service, resolver)?.getSpecificationId() ?: return null
        val runOptions = specmaticConfig.dependencies.getRunOptions(service, resolver, specType) ?: return null
        return runOptions.getMatchingSpecification(specificationId)
    }

    private fun getMockSettingsFor(specFile: File): MockSettings {
        val globalMockSettings = specmaticSettings.mock ?: MockSettings()
        val service = getMockService(specFile) ?: return globalMockSettings
        return specmaticConfig.dependencies?.getSettings(service,globalMockSettings, resolver) ?: globalMockSettings
    }

    private fun getMockSettings(): MockSettings {
        val globalMockSettings = specmaticSettings.mock ?: MockSettings()
        val mockSettings = specmaticConfig.dependencies?.settings?.resolveElseThrow(resolver)  ?: MockSettings()
        return mockSettings.merge(globalMockSettings)
    }

    private fun resolveSecurityToken(tokenFromConfig: String?, schemeName: String, defaultEnvVar: String? = null): String? {
        val configuredToken = tokenFromConfig?.takeIf { it.isNotBlank() }
        return configuredToken ?: getStringValue(schemeName) ?: defaultEnvVar?.let { getStringValue(it) }
    }

    override fun getLogConfigurationOrDefault(): LoggingConfiguration {
        return specmaticSettings.general?.logging ?: LoggingConfiguration.default()
    }

    override fun getSpecificationSources(): List<SpecificationSource> {
        val testSources = specmaticConfig.systemUnderTest?.getSpecificationSources(resolver, testSettings).orEmpty()
        val mockSources = specmaticConfig.dependencies?.getSpecificationSources(resolver).orEmpty()
        val allSources = (testSources.keys + mockSources.keys).distinct()
        return allSources.mapNotNull { source ->
            val sourceEntry = testSources[source]?.firstOrNull() ?: mockSources[source]?.firstOrNull() ?: return@mapNotNull null
            SpecificationSource(
                type = sourceEntry.type, repository = sourceEntry.repository, directory = sourceEntry.directory, branch = sourceEntry.branch,
                test = testSources[source].orEmpty(), mock = mockSources[source].orEmpty()
            )
        }
    }

    override fun getFirstMockSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        val mockSources = specmaticConfig.dependencies?.getSpecificationSources(resolver).orEmpty()
        return mockSources.values.flatten().firstOrNull(predicate)
    }

    override fun getFirstTestSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        val testSources = specmaticConfig.systemUnderTest?.getSpecificationSources(resolver, testSettings).orEmpty()
        return testSources.values.flatten().firstOrNull(predicate)
    }

    override fun isTelemetryDisabled(): Boolean {
        val disableTelemetry = specmaticSettings.general?.disableTelemetry
        val disableTelemetryFromEnvVarOrSystemProp = readEnvVarOrProperty(SPECMATIC_DISABLE_TELEMETRY, SPECMATIC_DISABLE_TELEMETRY)
        return disableTelemetryFromEnvVarOrSystemProp?.toBoolean() ?: (disableTelemetry == true)
    }

    override fun testConfigFor(specPath: String, specType: String): SpecmaticSpecConfig? {
        val specType = SpecType.entries.firstOrNull { it.value.equals(specType, ignoreCase = true) } ?: return null
        return testConfigFor(File(specPath), specType)
    }

    override fun testConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig? {
        val specId = specmaticConfig.systemUnderTest?.getSpecDefinitionFor(file, resolver)?.getSpecificationId()
        return when (val runOptions = specmaticConfig.systemUnderTest?.getRunOptions(resolver, specType)) {
            is AsyncApiRunOptions -> runOptions
            is GraphQLSdlRunOptions -> runOptions
            is ProtobufRunOptions -> runOptions
            else -> null
        }?.toSpecmaticSpecConfig(specId)
    }

    override fun stubConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig? {
        val service = specmaticConfig.dependencies?.getService(file, resolver) ?: return null
        val specId = specmaticConfig.dependencies.getSpecDefinitionFor(file, service, resolver)?.getSpecificationId()
        return when (val runOptions = specmaticConfig.dependencies.getRunOptions(service, resolver, specType)) {
            is AsyncApiRunOptions -> runOptions
            is GraphQLSdlRunOptions -> runOptions
            is ProtobufRunOptions -> runOptions
            else -> null
        }?.toSpecmaticSpecConfig(specId)
    }

    override fun stubConfigFor(specPath: String, specType: String): SpecmaticSpecConfig? {
        val specType = SpecType.entries.firstOrNull { it.value.equals(specType, ignoreCase = true) } ?: return null
        return stubConfigFor(File(specPath), specType)
    }

    override fun getCtrfSpecConfig(absoluteSpecPath: String, testType: String, protocol: String, specType: String): CtrfSpecConfig {
        val source = when (testType) {
            CONTRACT_TEST_TEST_TYPE -> specmaticConfig.systemUnderTest?.getSourcesContaining(File(absoluteSpecPath), resolver)
            else -> specmaticConfig.dependencies?.getSourcesContaining(File(absoluteSpecPath), resolver)
        }

        val specPathFromConfig = when(testType) {
            CONTRACT_TEST_TEST_TYPE -> testSpecPathFromConfigFor(absoluteSpecPath)
            else -> stubSpecPathFromConfigFor(absoluteSpecPath)
        }

        return CtrfSpecConfig(
            protocol = protocol,
            specType = specType,
            specification = specPathFromConfig.orEmpty(),
            sourceProvider = source?.toProviderType()?.name ?: SourceProvider.filesystem.name,
            repository = source?.getGit()?.url.orEmpty(),
            branch = source?.getGit()?.branch ?: "main",
        )
    }

    override fun getHotReload(): Switch? {
        val mockHotReload = getMockSettings().hotReload ?: return null
        return if (mockHotReload) Switch.enabled else Switch.disabled
    }

    override fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfig {
        return this
    }

    override fun getReport(): ReportConfiguration? {
        return specmaticConfig.specmatic?.governance
    }

    override fun getWorkflowDetails(): WorkflowDetails? {
        return specmaticConfig.systemUnderTest?.getOpenApiTestConfig(resolver)?.workflow
    }

    override fun getAttributeSelectionPattern(): AttributeSelectionPatternDetails {
        return AttributeSelectionPatternDetails.default
    }

    override fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings {
        return SpecmaticGlobalSettings(
            specExamplesDirectoryTemplate = specmaticSettings.general?.specExamplesDirectoryTemplate,
            sharedExamplesDirectoryTemplate = specmaticSettings.general?.sharedExamplesDirectoryTemplate
        )
    }

    override fun stubBaseUrls(defaultBaseUrl: String): List<String> {
        val stubEntries = specmaticConfig.dependencies?.getSpecificationSources(resolver)?.values.orEmpty().flatten()
        return stubEntries.map { it.baseUrl ?: defaultBaseUrl }.distinct()
    }

    override fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
        val stubEntries = specmaticConfig.dependencies?.getSpecificationSources(resolver)?.values.orEmpty().flatten()
        return stubEntries.map { stubEntry ->
            stubEntry.specPathInConfig to (stubEntry.baseUrl ?: defaultBaseUrl)
        }
    }

    override fun getStubStartTimeoutInMilliseconds(): Long {
        return getMockSettings().startTimeoutInMilliseconds ?: 20_000L
    }

    override fun logDependencyProjects(azure: AzureAPI) {
        return
    }

    override fun loadSources(useCurrentBranchForCentralRepo: Boolean): List<ContractSource> {
        val specificationSources = getSpecificationSources()
        return specificationSources.mapNotNull { specificationSourceEntry ->
            val testPaths = specificationSourceEntry.test.map { it.toContractSourceEntry() }
            val stubPaths = specificationSourceEntry.mock.map { it.toContractSourceEntry() }
            when (specificationSourceEntry.type) {
                SourceProvider.git -> {
                    val sourceMatchBranch = specificationSourceEntry.matchBranch ?: false
                    val effectiveUseCurrentBranch = useCurrentBranchForCentralRepo || sourceMatchBranch
                    val effectiveBranch = getEffectiveBranchForSource(specificationSourceEntry.branch, effectiveUseCurrentBranch)
                    when (specificationSourceEntry.repository) {
                        null -> GitMonoRepo(testPaths, stubPaths, specificationSourceEntry.type.toString())
                        else -> GitRepo(
                            specificationSourceEntry.repository,
                            effectiveBranch,
                            testPaths,
                            stubPaths,
                            specificationSourceEntry.type.toString(),
                            effectiveUseCurrentBranch,
                            specmaticConfig = this
                        )
                    }
                }
                SourceProvider.filesystem -> LocalFileSystemSource(specificationSourceEntry.directory ?: ".", testPaths, stubPaths)
                else -> null
            }
        }
    }

    override fun attributeSelectionQueryParamKey(): String {
        return AttributeSelectionPattern().getQueryParamKey()
    }

    override fun isExtensibleSchemaEnabled(): Boolean {
        return false
    }

    override fun isResponseValueValidationEnabled(): Boolean {
        return testSettings.validateResponseValues ?: getBooleanValue(VALIDATE_RESPONSE_VALUE)
    }

    override fun parsedDefaultPatternValues(): Map<String, Value> {
        return emptyMap()
    }

    override fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        val resiliencyFromProperty = ResiliencyTestsConfig.fromSystemProperties()
        val resiliencyTestSuite = (testSettings.resiliencyTests?.let(::ResiliencyTestsConfig) ?: resiliencyFromProperty)
        return resiliencyTestSuite.enable ?: ResiliencyTestSuite.none
    }

    override fun getTestTimeoutInMilliseconds(): Long? {
        return testSettings.timeoutInMilliseconds ?: getLongValue(SPECMATIC_TEST_TIMEOUT)
    }

    override fun getMaxTestRequestCombinations(): Int? {
        val configValue = testSettings.maxTestRequestCombinations
        return configValue ?: getIntValue(MAX_TEST_REQUEST_COMBINATIONS)
    }

    override fun getTestStrictMode(): Boolean? {
        return testSettings.strictMode ?: getStringValue(TEST_STRICT_MODE)?.toBoolean()
    }

    override fun getTestLenientMode(): Boolean? {
        return testSettings.lenientMode ?: getStringValue(TEST_LENIENT_MODE)?.toBoolean()
    }

    override fun getTestParallelism(): String? {
        return testSettings.parallelism ?: getStringValue(SPECMATIC_TEST_PARALLELISM)
    }

    override fun getTestsDirectory(): String? {
        return readEnvVarOrProperty(TESTS_DIRECTORY_ENV_VAR, TESTS_DIRECTORY_PROPERTY)
    }

    override fun getMaxTestCount(): Int? {
        return testSettings.maxTestCount ?: getIntValue(MAX_TEST_COUNT)
    }

    override fun getTestFilter(): String? {
        val runOpts = specmaticConfig.systemUnderTest?.getRunOptions(resolver, SpecType.OPENAPI) as? OpenApiTestConfig ?: return null
        return runOpts.filter ?: readEnvVarOrProperty(TEST_FILTER_ENV_VAR, TEST_FILTER_PROPERTY)
    }

    override fun getTestFilterName(): String? {
        return readEnvVarOrProperty(TEST_FILTER_NAME_ENV_VAR, TEST_FILTER_NAME_PROPERTY)
    }

    override fun getTestFilterNotName(): String? {
        return readEnvVarOrProperty(TEST_FILTER_NOT_NAME_ENV_VAR, TEST_FILTER_NOT_NAME_PROPERTY)
    }

    override fun getTestOverlayFilePath(specFile: File, specType: SpecType): String? {
        val specId = specmaticConfig.systemUnderTest?.getSpecDefinitionFor(specFile, resolver)?.getSpecificationId() ?: return null
        val runOpts = specmaticConfig.systemUnderTest.getRunOptions(resolver, specType) ?: return null
        return runOpts.getMatchingSpecification(specId)?.getOverlayFilePath()
    }

    override fun getTestBaseUrl(specType: SpecType): String? {
        val runOptions = specmaticConfig.systemUnderTest?.getRunOptions(resolver, specType)
        return runOptions?.baseUrl
    }

    override fun getCoverageReportBaseUrl(specType: SpecType): String? {
        val baseUrl = getTestBaseUrl(specType)
        if (baseUrl != null) return baseUrl

        val fromSysProp = readEnvVarOrProperty(TEST_BASE_URL_ENV_VAR, TEST_BASE_URL_PROPERTY)
        if (fromSysProp != null) return fromSysProp

        val host = readEnvVarOrProperty(TEST_HOST_ENV_VAR, TEST_HOST_PROPERTY).orEmpty()
        val port = readEnvVarOrProperty(TEST_PORT_ENV_VAR, TEST_PORT_PROPERTY).orEmpty()
        return if (host.isNotBlank() && port.isNotBlank()) "$host:$port" else null
    }

    override fun getTestSwaggerUrl(): String? {
        return specmaticConfig.systemUnderTest?.getOpenApiTestConfig(resolver)?.swaggerUrl
    }

    override fun getTestSwaggerUIBaseUrl(): String? {
        return specmaticConfig.systemUnderTest?.getOpenApiTestConfig(resolver)?.swaggerUiBaseUrl
    }

    override fun getTestJunitReportDir(): String? {
        return testSettings.junitReportDir
    }

    override fun getActuatorUrl(): String? {
        return specmaticConfig.systemUnderTest?.getOpenApiTestConfig(resolver)?.actuatorUrl
    }

    override fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig {
        val systemUnderTest = specmaticConfig.systemUnderTest ?: return this
        val resiliencyTestSuite = if (onlyPositive) ResiliencyTestSuite.positiveOnly else ResiliencyTestSuite.all
        val updatedSut = systemUnderTest.copyResiliencyTestsConfig(resolver, resiliencyTestSuite)
        return this.copy(specmaticConfig = specmaticConfig.copy(systemUnderTest = updatedSut))
    }

    override fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        return false
    }

    override fun getStubGenerative(specFile: File?): Boolean {
        val mockSettings = if (specFile != null) getMockSettingsFor(specFile) else getMockSettings()
        return mockSettings.generative ?: false
    }

    override fun getStubDelayInMilliseconds(specFile: File?): Long? {
        val mockSettings = if (specFile != null) getMockSettingsFor(specFile) else getMockSettings()
        return mockSettings.delayInMilliseconds ?: getLongValue(SPECMATIC_STUB_DELAY)
    }

    override fun getStubDictionary(specFile: File): String? {
        val fromSysProp = getStringValue(SPECMATIC_STUB_DICTIONARY)
        val fromMockService = getMockService(specFile)?.data?.dictionary?.resolveElseThrow(resolver)?.path
        val fromDependencies = specmaticConfig.dependencies?.data?.dictionary?.resolveElseThrow(resolver)?.path
        return fromMockService ?: fromDependencies ?: fromSysProp
    }

    override fun getStubStrictMode(specFile: File?): Boolean {
        val mockSettings = if (specFile != null) getMockSettingsFor(specFile) else getMockSettings()
        return mockSettings.strictMode ?: getBooleanValue(Flags.STUB_STRICT_MODE, false)
    }

    override fun getStubFilter(specFile: File): String? {
        val runOpts = getMockRunOptions(specFile, SpecType.OPENAPI) as? OpenApiMockConfig ?: return null
        return runOpts.filter
    }

    override fun getStubHttpsConfiguration(): CertRegistry {
        val registry = CertRegistry.empty()
        val certificates: List<Pair<String, HttpsConfiguration>> = specmaticConfig.dependencies?.getCerts(resolver).orEmpty()
        return certificates.fold(registry) { acc, (baseUrl, cert) -> acc.plus(baseUrl, cert) }
    }

    override fun getStubGracefulRestartTimeoutInMilliseconds(): Long? {
        val mockSettings = getMockSettings()
        return mockSettings.gracefulRestartTimeoutInMilliseconds
    }

    override fun getDefaultBaseUrl(): String {
        return getStringValue(SPECMATIC_BASE_URL) ?: Configuration.DEFAULT_BASE_URL
    }

    override fun getCustomImplicitStubBase(): String? {
        return readEnvVarOrProperty(CUSTOM_IMPLICIT_STUB_BASE_ENV_VAR, CUSTOM_IMPLICIT_STUB_BASE_PROPERTY)
    }

    override fun getIgnoreInlineExamples(): Boolean {
        return specmaticSettings.general?.ignoreInlineExamples ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLES)
    }

    override fun getIgnoreInlineExampleWarnings(): Boolean {
        return specmaticSettings.general?.ignoreInlineExampleWarnings ?: getBooleanValue(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS)
    }

    override fun getAllPatternsMandatory(): Boolean {
        return false
    }

    override fun getSchemaExampleDefault(): Boolean {
        return specmaticSettings.general?.featureFlags?.schemaExampleDefault ?: getBooleanValue(Flags.SCHEMA_EXAMPLE_DEFAULT)
    }

    override fun getFuzzyMatchingEnabled(): Boolean {
        return specmaticSettings.general?.featureFlags?.fuzzyMatcherForPayloads ?: getBooleanValue(Flags.SPECMATIC_FUZZY)
    }

    override fun getExtensibleQueryParams(): Boolean {
        return getBooleanValue(EXTENSIBLE_QUERY_PARAMS)
    }

    override fun getEscapeSoapAction(): Boolean {
        return specmaticSettings.general?.featureFlags?.escapeSoapAction ?: getBooleanValue(SPECMATIC_ESCAPE_SOAP_ACTION)
    }

    override fun getPrettyPrint(): Boolean {
        return specmaticSettings.general?.prettyPrint ?: getBooleanValue(SPECMATIC_PRETTY_PRINT, true)
    }

    override fun getAdditionalExampleParamsFilePath(): String? {
        return getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)
    }

    override fun getHooks(): Map<String, String> {
        val fromDependencies = specmaticConfig.dependencies?.data?.adapters?.resolveElseThrow(resolver)?.hooks
        return fromDependencies.orEmpty()
    }

    override fun getProxyConfig(): ProxyConfig? {
        val proxyConfig = specmaticConfig.proxies?.firstOrNull()?.proxy ?: return null
        return proxyConfig.toCommonConfig(resolver)
    }

    override fun getDefaultPatternValues(): Map<String, Any> {
        return emptyMap()
    }

    override fun getVersion(): SpecmaticConfigVersion {
        return specmaticConfig.version
    }

    override fun getMatchBranchEnabled(): Boolean {
        val testSources = specmaticConfig.systemUnderTest?.getSources(resolver).orEmpty()
        val mockSources = specmaticConfig.dependencies?.getSources(resolver).orEmpty()
        val gitSources = testSources.plus(mockSources).mapNotNull { it.getGit() }
        return gitSources.any { it.matchBranch == true }
    }

    override fun getAuth(repositoryUrl: String): Auth? {
        val testSources = specmaticConfig.systemUnderTest?.getSources(resolver).orEmpty()
        val mockSources = specmaticConfig.dependencies?.getSources(resolver).orEmpty()
        val gitSources = testSources.plus(mockSources).mapNotNull { it.getGit() }
        return gitSources.firstOrNull { it.url == repositoryUrl }?.auth?.toCommonAuth()
    }

    override fun getAuthBearerFile(repositoryUrl: String): String? {
        val testSources = specmaticConfig.systemUnderTest?.getSources(resolver).orEmpty()
        val mockSources = specmaticConfig.dependencies?.getSources(resolver).orEmpty()
        val gitSources = testSources.plus(mockSources).mapNotNull { it.getGit() }
        return (gitSources.firstOrNull { it.url == repositoryUrl }?.auth as? GitAuthentication.BearerFile)?.bearerFile
    }

    override fun getAuthBearerEnvironmentVariable(repositoryUrl: String): String? {
        val testSources = specmaticConfig.systemUnderTest?.getSources(resolver).orEmpty()
        val mockSources = specmaticConfig.dependencies?.getSources(resolver).orEmpty()
        val gitSources = testSources.plus(mockSources).mapNotNull { it.getGit() }
        return (gitSources.firstOrNull { it.url == repositoryUrl }?.auth as? GitAuthentication.BearerEnv)?.bearerEnvironmentVariable
    }

    override fun getAuthPersonalAccessToken(repositoryUrl: String): String? {
        val testSources = specmaticConfig.systemUnderTest?.getSources(resolver).orEmpty()
        val mockSources = specmaticConfig.dependencies?.getSources(resolver).orEmpty()
        val gitSources = testSources.plus(mockSources).mapNotNull { it.getGit() }
        return (gitSources.firstOrNull { it.url == repositoryUrl }?.auth as? GitAuthentication.PersonalAccessToken)?.personalAccessToken
    }

    override fun getTestExampleDirs(specFile: File): List<String> {
        return specmaticConfig.systemUnderTest?.getExampleDirs(resolver).orEmpty()
    }

    override fun getExamples(): List<String> {
        return emptyList()
    }

    override fun getRepositoryProvider(): String? {
        return null
    }

    override fun getRepositoryCollectionName(): String? {
        return null
    }

    override fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig? {
        return specmaticSettings.backwardCompatibility
    }

    override fun getMcpConfiguration(): McpConfiguration? {
        return specmaticConfig.mcp
    }

    override fun getPipelineProvider(): PipelineProvider? {
        return null
    }

    override fun getPipelineDefinitionId(): Int? {
        return null
    }

    override fun getPipelineOrganization(): String? {
        return null
    }

    override fun getPipelineProject(): String? {
        return null
    }

    override fun getOpenAPISecurityConfigurationScheme(specFile: File, scheme: String): SecuritySchemeConfiguration? {
        val specId = specmaticConfig.systemUnderTest?.getSpecDefinitionFor(specFile, resolver)?.getSpecificationId() ?: return null
        val testRunOpts = specmaticConfig.systemUnderTest.getRunOptions(resolver, SpecType.OPENAPI) ?: return null
        val specData = testRunOpts.getMatchingSpecification(specId) as? OpenApiRunOptionsSpecifications ?: return null
        val matchingScheme = specData.getSecuritySchemes()?.get(scheme) ?: return null
        return matchingScheme.toSecuritySchemeConfiguration()
    }

    override fun getBasicAuthSecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? BasicAuthSecuritySchemeConfiguration)?.token
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_BASIC_AUTH_TOKEN)
    }

    override fun getBearerSecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? SecuritySchemeWithOAuthToken)?.token
        return resolveSecurityToken(tokenFromConfig, schemeName, SPECMATIC_OAUTH2_TOKEN)
    }

    override fun getApiKeySecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String? {
        val tokenFromConfig = (securitySchemeConfiguration as? APIKeySecuritySchemeConfiguration)?.value
        return resolveSecurityToken(tokenFromConfig, schemeName)
    }

    override fun getVirtualServiceHost(): String? {
        return null // TODO: Modify so VS can work
    }

    override fun getVirtualServicePort(): Int? {
        return null // TODO: Modify so VS can work
    }

    override fun getVirtualServiceSpecs(): List<String>? {
        return null // TODO: Modify so VS can work
    }

    override fun getVirtualServiceSpecsDirPath(): String? {
        return null // TODO: Modify so VS can work
    }

    override fun getVirtualServiceLogsDirPath(): String? {
        return null // TODO: Modify so VS can work
    }

    override fun getVirtualServiceLogMode(): VirtualServiceConfiguration.VSLogMode? {
        return null // TODO: Modify so VS can work
    }

    override fun getVirtualServiceNonPatchableKeys(): Set<String> {
        return emptySet() // TODO: Modify so VS can work
    }

    override fun stubContracts(relativeTo: File): List<String> {
        val stubFiles = specmaticConfig.dependencies?.getAllSpecifications(resolver) ?: return emptyList()
        return stubFiles.map { specFile ->
            relativeTo.parentFile?.resolve(specFile)?.canonicalPath ?: File(specFile).canonicalPath
        }
    }

    override fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig {
        val specmaticSettings = specmaticConfig.specmatic ?: Specmatic()
        val existingGovernance = specmaticSettings.governance ?: Governance()
        val updatedGovernance = existingGovernance.copy(successCriteria = SuccessCriterion.from(reportConfiguration.getSuccessCriteria()), report = existingGovernance.report)
        return this.copy(specmaticConfig = specmaticConfig.copy(specmatic = specmaticSettings.copy(governance = updatedGovernance)))
    }

    override fun getEnvironment(envName: String): JSONObjectValue {
        return JSONObjectValue(emptyMap())
    }

    override fun enableResiliencyTests(): SpecmaticConfig {
        return copyResiliencyTestsConfig(onlyPositive = false)
    }

    override fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfig {
        val systemUnderTest = specmaticConfig.systemUnderTest ?: return this
        val updatedSut = systemUnderTest.withTestMode(resolver, strictMode, lenientMode)
        return this.copy(specmaticConfig = specmaticConfig.copy(systemUnderTest = updatedSut))
    }

    override fun withTestFilter(filter: String?): SpecmaticConfig {
        if (filter == null) return this
        val systemUnderTest = specmaticConfig.systemUnderTest ?: return this
        val updatedSut = systemUnderTest.withTestFilter(resolver, filter)
        return this.copy(specmaticConfig = specmaticConfig.copy(systemUnderTest = updatedSut))
    }

    override fun withTestTimeout(timeoutInMilliseconds: Long?): SpecmaticConfig {
        val systemUnderTest = specmaticConfig.systemUnderTest ?: return this
        val updatedSut = systemUnderTest.withTestTimeout(resolver, timeoutInMilliseconds)
        return this.copy(specmaticConfig = specmaticConfig.copy(systemUnderTest = updatedSut))
    }

    override fun withStubModes(strictMode: Boolean?): SpecmaticConfig {
        if (strictMode == null) return this
        val updatedDependencies = specmaticConfig.dependencies?.withStubMode(resolver, strictMode) ?: return this
        return this.copy(specmaticConfig = specmaticConfig.copy(dependencies = updatedDependencies))
    }

    override fun withStubFilter(filter: String?): SpecmaticConfig {
        if (filter == null) return this
        val updatedDependencies = specmaticConfig.dependencies?.withStubFilter(resolver, filter) ?: return this
        return this.copy(specmaticConfig = specmaticConfig.copy(dependencies = updatedDependencies))
    }

    override fun withGlobalMockDelay(delayInMilliseconds: Long): SpecmaticConfig {
        val updatedDependencies = specmaticConfig.dependencies?.withGlobalDelay(resolver, delayInMilliseconds) ?: return this
        return this.copy(specmaticConfig = specmaticConfig.copy(dependencies = updatedDependencies))
    }

    override fun withMatchBranch(matchBranch: Boolean): SpecmaticConfig {
        val updatedSut = specmaticConfig.systemUnderTest?.withMatchBranch(resolver, matchBranch)
        val updatedDependencies = specmaticConfig.dependencies?.withMatchBranch(resolver, matchBranch)
        val updatedConfig = specmaticConfig.copy(systemUnderTest = updatedSut, dependencies = updatedDependencies)
        return this.copy(specmaticConfig = updatedConfig)
    }

    override fun testSpecPathFromConfigFor(absoluteSpecPath: String): String? {
        val specDefinition = specmaticConfig.systemUnderTest?.getSpecDefinitionFor(File(absoluteSpecPath), resolver)
        return specDefinition?.getSpecificationPath()
    }

    override fun stubSpecPathFromConfigFor(absoluteSpecPath: String): String? {
        val specDefinition = specmaticConfig.systemUnderTest?.getSpecDefinitionFor(File(absoluteSpecPath), resolver)
        return specDefinition?.getSpecificationPath()
    }

    override fun getLicensePath(): Path? {
        return specmaticConfig.specmatic?.license?.path
    }

    override fun getReportDirPath(suffix: String?): Path {
        val reportDirPath = specmaticConfig.specmatic?.governance?.report?.outputDirectory ?: defaultReportDirPath
        if (suffix == null) return reportDirPath
        return reportDirPath.resolve(suffix)
    }

    override fun plusExamples(exampleDirectories: List<String>): SpecmaticConfig {
        val updatedSut = specmaticConfig.systemUnderTest?.withExamples(resolver, exampleDirectories)
        val updatedDependencies = specmaticConfig.dependencies?.withExamples(resolver, exampleDirectories)
        val updatedConfig = specmaticConfig.copy(systemUnderTest = updatedSut, dependencies = updatedDependencies)
        return this.copy(specmaticConfig = updatedConfig)
    }

    override fun getSecurityConfiguration(specFile: File): SecurityConfiguration? {
        val specId = specmaticConfig.systemUnderTest?.getSpecDefinitionFor(specFile, resolver)?.getSpecificationId() ?: return null
        val testRunOpts = specmaticConfig.systemUnderTest.getRunOptions(resolver, SpecType.OPENAPI) ?: return null
        val specData = testRunOpts.getMatchingSpecification(specId) as? OpenApiRunOptionsSpecifications ?: return null
        val schemes = specData.getSecuritySchemes()?.mapValues { it.value.toSecuritySchemeConfiguration() } ?: return null
        return SecurityConfiguration(OpenAPI = OpenAPISecurityConfiguration(schemes))
    }
}