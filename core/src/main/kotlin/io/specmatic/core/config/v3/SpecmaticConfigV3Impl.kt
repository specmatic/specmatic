package io.specmatic.core.config.v3

import io.specmatic.core.AttributeSelectionPatternDetails
import io.specmatic.core.Auth
import io.specmatic.core.PipelineProvider
import io.specmatic.core.ProxyConfig
import io.specmatic.core.ReportConfiguration
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.SecuritySchemeConfiguration
import io.specmatic.core.SpecificationSource
import io.specmatic.core.SpecificationSourceEntry
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.VirtualServiceConfiguration
import io.specmatic.core.WorkflowDetails
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.Switch
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import java.io.File
import java.nio.file.Path

class SpecmaticConfigV3Impl(file: File? = null, private val specmaticConfig: SpecmaticConfigV3) : SpecmaticConfig {
    private val effectiveWorkingFile: File = file?.canonicalFile ?: File(".").canonicalFile
    private val resolver = SpecmaticConfigV3Resolver(specmaticConfig.components ?: Components(), effectiveWorkingFile.toPath())

    override fun getLogConfigurationOrDefault(): LoggingConfiguration {
        TODO("Not yet implemented")
    }

    override fun getSpecificationSources(): List<SpecificationSource> {
        TODO("Not yet implemented")
    }

    override fun getFirstMockSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        TODO("Not yet implemented")
    }

    override fun getFirstTestSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry? {
        TODO("Not yet implemented")
    }

    override fun isTelemetryDisabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun testConfigFor(specPath: String, specType: String): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun testConfigFor(file: File, specType: String): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun stubConfigFor(file: File, specType: String): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun stubConfigFor(specPath: String, specType: String): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun getCtrfSpecConfig(absoluteSpecPath: String, testType: String, protocol: String, specType: String): CtrfSpecConfig {
        TODO("Not yet implemented")
    }

    override fun getHotReload(): Switch? {
        TODO("Not yet implemented")
    }

    override fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun getReport(): ReportConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getWorkflowDetails(): WorkflowDetails? {
        TODO("Not yet implemented")
    }

    override fun getAttributeSelectionPattern(): AttributeSelectionPatternDetails {
        TODO("Not yet implemented")
    }

    override fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings {
        TODO("Not yet implemented")
    }

    override fun stubBaseUrls(defaultBaseUrl: String): List<String> {
        TODO("Not yet implemented")
    }

    override fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>> {
        TODO("Not yet implemented")
    }

    override fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String {
        TODO("Not yet implemented")
    }

    override fun getStubStartTimeoutInMilliseconds(): Long {
        TODO("Not yet implemented")
    }

    override fun logDependencyProjects(azure: AzureAPI) {
        TODO("Not yet implemented")
    }

    override fun loadSources(useCurrentBranchForCentralRepo: Boolean): List<ContractSource> {
        TODO("Not yet implemented")
    }

    override fun attributeSelectionQueryParamKey(): String {
        TODO("Not yet implemented")
    }

    override fun isExtensibleSchemaEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isResiliencyTestingEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isOnlyPositiveTestingEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isResponseValueValidationEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun parsedDefaultPatternValues(): Map<String, Value> {
        TODO("Not yet implemented")
    }

    override fun getResiliencyTestsEnabled(): ResiliencyTestSuite {
        TODO("Not yet implemented")
    }

    override fun getTestTimeoutInMilliseconds(): Long? {
        TODO("Not yet implemented")
    }

    override fun getMaxTestRequestCombinations(): Int? {
        TODO("Not yet implemented")
    }

    override fun getTestStrictMode(): Boolean? {
        TODO("Not yet implemented")
    }

    override fun getTestLenientMode(): Boolean? {
        TODO("Not yet implemented")
    }

    override fun getTestParallelism(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestsDirectory(): String? {
        TODO("Not yet implemented")
    }

    override fun getMaxTestCount(): Int? {
        TODO("Not yet implemented")
    }

    override fun getTestFilter(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestFilterName(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestFilterNotName(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestOverlayFilePath(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestBaseUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getCoverageReportBaseUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestSwaggerUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestSwaggerUIBaseUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun getTestJunitReportDir(): String? {
        TODO("Not yet implemented")
    }

    override fun getActuatorUrl(): String? {
        TODO("Not yet implemented")
    }

    override fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getStubGenerative(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getStubDelayInMilliseconds(): Long? {
        TODO("Not yet implemented")
    }

    override fun getStubDictionary(): String? {
        TODO("Not yet implemented")
    }

    override fun getStubStrictMode(): Boolean? {
        TODO("Not yet implemented")
    }

    override fun getStubFilter(): String? {
        TODO("Not yet implemented")
    }

    override fun getStubHttpsConfiguration(): HttpsConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getStubGracefulRestartTimeoutInMilliseconds(): Long? {
        TODO("Not yet implemented")
    }

    override fun getDefaultBaseUrl(): String {
        TODO("Not yet implemented")
    }

    override fun getCustomImplicitStubBase(): String? {
        TODO("Not yet implemented")
    }

    override fun getIgnoreInlineExamples(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getIgnoreInlineExampleWarnings(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getAllPatternsMandatory(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getSchemaExampleDefault(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getFuzzyMatchingEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getExtensibleQueryParams(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getEscapeSoapAction(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getPrettyPrint(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getAdditionalExampleParamsFilePath(): String? {
        TODO("Not yet implemented")
    }

    override fun getHooks(): Map<String, String> {
        TODO("Not yet implemented")
    }

    override fun getProxyConfig(): ProxyConfig? {
        TODO("Not yet implemented")
    }

    override fun getDefaultPatternValues(): Map<String, Any> {
        TODO("Not yet implemented")
    }

    override fun getVersion(): SpecmaticConfigVersion {
        TODO("Not yet implemented")
    }

    override fun getMatchBranchEnabled(): Boolean {
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

    override fun getExamples(): List<String> {
        TODO("Not yet implemented")
    }

    override fun getRepositoryProvider(): String? {
        TODO("Not yet implemented")
    }

    override fun getRepositoryCollectionName(): String? {
        TODO("Not yet implemented")
    }

    override fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig? {
        TODO("Not yet implemented")
    }

    override fun getMcpConfiguration(): McpConfiguration? {
        TODO("Not yet implemented")
    }

    override fun getPipelineProvider(): PipelineProvider? {
        TODO("Not yet implemented")
    }

    override fun getPipelineDefinitionId(): Int? {
        TODO("Not yet implemented")
    }

    override fun getPipelineOrganization(): String? {
        TODO("Not yet implemented")
    }

    override fun getPipelineProject(): String? {
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

    override fun getVirtualServiceHost(): String? {
        TODO("Not yet implemented")
    }

    override fun getVirtualServicePort(): Int? {
        TODO("Not yet implemented")
    }

    override fun getVirtualServiceSpecs(): List<String>? {
        TODO("Not yet implemented")
    }

    override fun getVirtualServiceSpecsDirPath(): String? {
        TODO("Not yet implemented")
    }

    override fun getVirtualServiceLogsDirPath(): String? {
        TODO("Not yet implemented")
    }

    override fun getVirtualServiceLogMode(): VirtualServiceConfiguration.VSLogMode? {
        TODO("Not yet implemented")
    }

    override fun getVirtualServiceNonPatchableKeys(): Set<String> {
        TODO("Not yet implemented")
    }

    override fun stubContracts(relativeTo: File): List<String> {
        TODO("Not yet implemented")
    }

    override fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun getEnvironment(envName: String): JSONObjectValue {
        TODO("Not yet implemented")
    }

    override fun enableResiliencyTests(): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withTestFilter(filter: String?): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withTestTimeout(timeoutInMilliseconds: Long?): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withStubModes(strictMode: Boolean?): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withStubFilter(filter: String?): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withGlobalMockDelay(delayInMilliseconds: Long): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun withMatchBranch(matchBranch: Boolean): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun testSpecPathFromConfigFor(absoluteSpecPath: String): String? {
        TODO("Not yet implemented")
    }

    override fun stubSpecPathFromConfigFor(absoluteSpecPath: String): String? {
        TODO("Not yet implemented")
    }

    override fun getLicensePath(): Path? {
        TODO("Not yet implemented")
    }

    override fun getReportDirPath(suffix: String?): Path {
        TODO("Not yet implemented")
    }

    override fun plusExamples(exampleDirectories: List<String>): SpecmaticConfig {
        TODO("Not yet implemented")
    }

    override fun getSecurityConfiguration(): SecurityConfiguration? {
        TODO("Not yet implemented")
    }
}