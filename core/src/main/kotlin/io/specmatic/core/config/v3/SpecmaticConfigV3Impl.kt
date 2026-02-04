package io.specmatic.core.config.v3

import io.specmatic.core.ProxyConfig
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.Auth
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.ReportConfiguration
import io.specmatic.core.config.ResiliencyTestSuite
import io.specmatic.core.config.SecurityConfiguration
import io.specmatic.core.config.SecuritySchemeConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.StubConfiguration
import io.specmatic.core.config.Switch
import io.specmatic.core.config.TestConfiguration
import io.specmatic.core.config.VirtualServiceConfiguration
import io.specmatic.core.config.WorkflowConfiguration
import io.specmatic.core.config.WorkflowDetails
import io.specmatic.core.config.v2.AttributeSelectionPatternDetails
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.SpecType
import java.io.File
import java.nio.file.Path

class SpecmaticConfigV3Impl(file: File? = null, private val specmaticConfig: SpecmaticConfigV3) : SpecmaticConfig {
    private val effectiveWorkingFile: File = file?.canonicalFile ?: File(".").canonicalFile
    private val resolver = SpecmaticConfigV3Resolver(specmaticConfig.components ?: Components(), effectiveWorkingFile.toPath())

    private fun getSpecmaticSettings(): ConcreteSettings {
        val settingsOrRef = specmaticConfig.specmatic?.settings ?: return ConcreteSettings()
        return settingsOrRef.resolveElseThrow(resolver)
    }

    override fun getVersion(): SpecmaticConfigVersion {
        return specmaticConfig.version
    }

    override fun loadSources(useCurrentBranchForCentralRepo: Boolean): List<ContractSource> {
        TODO("Not yet implemented")
    }

    override fun getLogConfigurationOrDefault(): LoggingConfiguration {
        val settings = getSpecmaticSettings()
        return settings.general?.logging ?: LoggingConfiguration.default()
    }

    override fun isTelemetryDisabled(): Boolean {
        val settings = getSpecmaticSettings()
        return settings.general?.disableTelemetry ?: false
    }

    override fun getTestConfiguration(specFile: File, specType: SpecType): TestConfiguration {
        val globalTestSettings = getSpecmaticSettings().test
        if (specmaticConfig.systemUnderTest == null) return TestConfiguration(globalTestSettings)
        val runOptions = specmaticConfig.systemUnderTest.getRunOptions(resolver, specType)
        val specificationDefinition = specmaticConfig.systemUnderTest.getSpecDefinitionFor(specFile, resolver)
        val specificationTestSettings = specmaticConfig.systemUnderTest.getSettings(globalTestSettings, resolver)
        return if (specificationDefinition?.getSpecificationId() == null) {
            TestConfiguration(specificationTestSettings, runOptions, null, specificationDefinition)
        } else {
            val runSpecOptions = runOptions?.getMatchingSpecification(specificationDefinition.getSpecificationId().orEmpty())
            TestConfiguration(specificationTestSettings, runOptions, runSpecOptions, specificationDefinition)
        }
    }

    override fun getStubConfiguration(specFile: File, specType: SpecType): StubConfiguration? {
        val globalMockSettings = getSpecmaticSettings().mock
        val service = specmaticConfig.dependencies?.getService(specFile, resolver) ?: return StubConfiguration(globalMockSettings)
        val dictionary = specmaticConfig.dependencies.getDictionary(service, resolver)
        val runOptions = specmaticConfig.dependencies.getRunOptions(service, resolver, specType)
        val cert = runOptions?.let { specmaticConfig.dependencies.getHttpsConfiguration(it, resolver)  }
        val specificationDefinition = specmaticConfig.dependencies.getSpecDefinitionFor(specFile, service, resolver)
        val specificationMockSettings = specmaticConfig.dependencies.getSettings(service, globalMockSettings, resolver)
        return if (specificationDefinition?.getSpecificationId() == null) {
            StubConfiguration(specificationMockSettings, dictionary, runOptions, cert, null, specificationDefinition)
        } else {
            val runSpecOptions = runOptions?.getMatchingSpecification(specificationDefinition.getSpecificationId().orEmpty())
            StubConfiguration(specificationMockSettings, dictionary, runOptions, cert, runSpecOptions, specificationDefinition)
        }
    }

    override fun testConfigFor(file: File, specType: SpecType): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun stubConfigFor(file: File, specType: SpecType): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun getVirtualServiceConfiguration(): VirtualServiceConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getCtrfSpecConfig(absoluteSpecPath: String, testType: String, key: String, value: String): CtrfSpecConfig? {
        TODO("Not yet implemented")
    }

    override fun getAttributeSelectionPatternDetails(): AttributeSelectionPatternDetails {
        return AttributeSelectionPatternDetails.default
    }

    override fun parsedDefaultPatternValues(): Map<String, Value> {
        return emptyMap()
    }

    override fun isExtensibleSchemaEnabledForTest(specFile: File, specType: SpecType): Boolean {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.allowExtensibleSchema ?: Flags.getBooleanValue(Flags.EXTENSIBLE_SCHEMA)
    }

    override fun isExtensibleSchemaEnabledForMock(specFile: File, specType: SpecType): Boolean {
        val globalMockSettings = getSpecmaticSettings().mock
        val service = specmaticConfig.dependencies?.getService(specFile, resolver) ?: return Flags.getBooleanValue(Flags.EXTENSIBLE_SCHEMA)
        val mockSettings = specmaticConfig.dependencies.getSettings(service, globalMockSettings, resolver)
        return mockSettings?.allowExtensibleSchema ?: Flags.getBooleanValue(Flags.EXTENSIBLE_SCHEMA)
    }

    override fun getMatchBranchEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getIgnoreInlineExamples(): Boolean {
        return getSpecmaticSettings().general?.ignoreInlineExamples ?: false
    }

    override fun getIgnoreInlineExampleWarnings(): Boolean {
        return getSpecmaticSettings().general?.ignoreInlineExampleWarnings ?: false
    }

    override fun getAllPatternsMandatory(): Boolean {
        return false
    }

    override fun getSchemaExampleDefault(): Boolean {
        return getSpecmaticSettings().general?.featureFlags?.schemaExampleDefault ?: false
    }

    override fun getFuzzyMatchingEnabled(): Boolean {
        return getSpecmaticSettings().general?.featureFlags?.fuzzyMatcherForPayloads ?: false
    }

    override fun getExtensibleQueryParams(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getEscapeSoapAction(): Boolean {
        return getSpecmaticSettings().general?.featureFlags?.escapeSoapAction ?: false
    }

    override fun getPrettyPrint(): Boolean {
        return getSpecmaticSettings().general?.prettyPrint ?: false
    }

    override fun getWorkflowConfiguration(): WorkflowConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getWorkflowDetails(): WorkflowDetails? {
        TODO("Not yet implemented")
    }

    override fun getTestBaseUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getCoverageReportBaseUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestTimeoutInMilliseconds(): Long? {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.timeoutInMilliseconds
    }

    override fun getMaxTestRequestCombinations(): Int? {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.maxTestRequestCombinations
    }

    override fun getTestStrictMode(): Boolean? {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.strictMode
    }

    override fun getTestLenientMode(): Boolean? {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.lenientMode
    }

    override fun getTestParallelism(): String? {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.parallelism
    }

    override fun getTestsDirectory(): String? {
        return null
    }

    override fun getMaxTestCount(): Int? {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.maxTestCount
    }

    override fun getTestFilter(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestFilterName(): String? {
        return null
    }

    override fun getTestFilterNotName(): String? {
        return null
    }

    override fun getTestOverlayFilePath(): String? {
        TODO("Not yet implemented")
    }

    override fun isResponseValueValidationEnabled(): Boolean {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.validateResponseValues ?: false
    }

    override fun getAdditionalExampleParamsFilePath(): File? {
        return null
    }

    override fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.resiliencyTests ?: ResiliencyTestSuite.positiveOnly
    }

    override fun isResiliencyTestingEnabled(): Boolean {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.resiliencyTests != null && testSettings.resiliencyTests != ResiliencyTestSuite.none
    }

    override fun isOnlyPositiveTestingEnabled(): Boolean {
        val globalTestSettings = getSpecmaticSettings().test
        val systemUnderTest = specmaticConfig.systemUnderTest
        val testSettings = systemUnderTest?.getSettings(globalTestSettings, resolver)
        return testSettings?.resiliencyTests == ResiliencyTestSuite.positiveOnly
    }

    override fun getTestSwaggerUIBaseUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestSwaggerUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getActuatorUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getSecurityConfiguration(): SecurityConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getHotReload(): Switch? {
        TODO("Not yet implemented")
    }

    override fun getDefaultBaseUrl(): String {
        TODO("Not yet implemented")
    }

    override fun getStubDelayInMilliseconds(): Long? {
        TODO("Not yet implemented")
    }

    override fun getStubStrictMode(): Boolean? {
        TODO("Not yet implemented")
    }

    override fun getStubFilter(): String? {
        TODO("Not yet implemented")
    }

    override fun getStubGenerative(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getStubDictionary(): String? {
        TODO("Not yet implemented")
    }

    override fun getStubHttpsConfiguration(): HttpsConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getStubGracefulRestartTimeoutInMilliseconds(): Long? {
        TODO("Not yet implemented")
    }

    override fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getStubStartTimeoutInMilliseconds(): Long {
        TODO("Not yet implemented")
    }

    override fun getCustomImplicitStubBase(): String? {
        TODO("Not yet implemented")
    }

    override fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
        TODO("Not yet implemented")
    }

    override fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String {
        TODO("Not yet implemented")
    }

    override fun getAuth(): Auth? {
        TODO("Not yet implemented")
    }

    override fun getAuthBearerFile(): String? {
        TODO("Not yet implemented")
    }

    override fun getAuthBearerEnvironmentVariable(): String? {
        TODO("Not yet implemented")
    }

    override fun getAuthPersonalAccessToken(): String? {
        TODO("Not yet implemented")
    }

    override fun getOpenAPISecurityConfigurationScheme(scheme: String): SecuritySchemeConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getBasicAuthSecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String? {
        TODO("Not yet implemented")
    }

    override fun getBearerSecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String? {
        TODO("Not yet implemented")
    }

    override fun getApiKeySecurityToken(schemeName: String, securitySchemeConfiguration: SecuritySchemeConfiguration?): String? {
        TODO("Not yet implemented")
    }

    override fun getTestJunitReportDir(): String? {
        TODO("Not yet implemented")
    }

    override fun getReport(): ReportConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getReportDirPath(suffix: String?): Path {
        TODO("Not yet implemented")
    }

    override fun getExamples(): List<String> {
        TODO("Not yet implemented")
    }

    override fun getEnvironment(envName: String): JSONObjectValue {
        TODO("Not yet implemented")
    }

    override fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings {
        TODO("Not yet implemented")
    }

    override fun getHooks(): Map<String, String> {
        TODO("Not yet implemented")
    }

    override fun getProxyConfig(): ProxyConfig? {
        TODO("Not yet implemented")
    }

    override fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig? {
        TODO("Not yet implemented")
    }

    override fun getMcpConfiguration(): McpConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getLicensePath(): Path? {
        return specmaticConfig.specmatic?.license?.path?.let(Path::of)
    }

    override fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun dropExcludedEndpointsAfterVersion1(version: SpecmaticConfigVersion): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withTestFilter(filter: String): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun enableResiliencyTests(): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withExamples(exampleDirectories: List<String>): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withTestTimeout(timeoutInMilliSeconds: Long): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withUseCurrentBranchForCentralRepo(useCurrentBranchForCentralRepo: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withStubModes(strictMode: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withStubFilter(filter: String): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withGlobalMockDelay(delay: Long): SpecmaticConfig {
        TODO("Not yet implemented")
    }
}