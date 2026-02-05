package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonIgnore
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

interface SpecmaticConfig {
    @JsonIgnore
    fun getLogConfigurationOrDefault(): LoggingConfiguration

    @JsonIgnore
    fun isTelemetryDisabled(): Boolean

    @JsonIgnore
    fun testConfigFor(specPath: String, specType: String): Map<String, Any>

    @JsonIgnore
    fun testConfigFor(file: File, specType: String): Map<String, Any>

    @JsonIgnore
    fun stubConfigFor(file: File, specType: String): Map<String, Any>

    @JsonIgnore
    fun stubConfigFor(specPath: String, specType: String): Map<String, Any>

    @JsonIgnore
    fun getCtrfSpecConfig(
        absoluteSpecPath: String,
        testType: String,
        protocol: String,
        specType: String
    ): CtrfSpecConfig

    @JsonIgnore
    fun getHotReload(): Switch?

    @JsonIgnore
    fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfigV1V2Common

    @JsonIgnore
    fun getReport(): ReportConfiguration?

    @JsonIgnore
    fun getWorkflowDetails(): WorkflowDetails?

    @JsonIgnore
    fun getAttributeSelectionPattern(): AttributeSelectionPatternDetails

    @JsonIgnore
    fun getGlobalSettingsOrDefault(): SpecmaticGlobalSettings

    @JsonIgnore
    fun stubBaseUrls(defaultBaseUrl: String): List<String>

    @JsonIgnore
    fun stubToBaseUrlList(defaultBaseUrl: String): List<Pair<String, String>>

    @JsonIgnore
    fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String

    @JsonIgnore
    fun getStubStartTimeoutInMilliseconds(): Long
    fun logDependencyProjects(azure: AzureAPI)

    @JsonIgnore
    fun loadSources(useCurrentBranchForCentralRepo: Boolean = false): List<ContractSource>

    @JsonIgnore
    fun attributeSelectionQueryParamKey(): String

    @JsonIgnore
    fun isExtensibleSchemaEnabled(): Boolean

    @JsonIgnore
    fun isResiliencyTestingEnabled(): Boolean

    @JsonIgnore
    fun isOnlyPositiveTestingEnabled(): Boolean

    @JsonIgnore
    fun isResponseValueValidationEnabled(): Boolean

    @JsonIgnore
    fun parsedDefaultPatternValues(): Map<String, Value>

    @JsonIgnore
    fun getResiliencyTestsEnabled(): ResiliencyTestSuite

    @JsonIgnore
    fun getTestTimeoutInMilliseconds(): Long?

    @JsonIgnore
    fun getMaxTestRequestCombinations(): Int?

    @JsonIgnore
    fun getTestStrictMode(): Boolean?

    @JsonIgnore
    fun getTestLenientMode(): Boolean?

    @JsonIgnore
    fun getTestParallelism(): String?

    @JsonIgnore
    fun getTestsDirectory(): String?

    @JsonIgnore
    fun getMaxTestCount(): Int?

    @JsonIgnore
    fun getTestFilter(): String?

    @JsonIgnore
    fun getTestFilterName(): String?

    @JsonIgnore
    fun getTestFilterNotName(): String?

    @JsonIgnore
    fun getTestOverlayFilePath(): String?

    @JsonIgnore
    fun getTestBaseUrl(): String?

    @JsonIgnore
    fun getCoverageReportBaseUrl(): String?

    @JsonIgnore
    fun getTestSwaggerUrl(): String?

    @JsonIgnore
    fun getTestSwaggerUIBaseUrl(): String?

    @JsonIgnore
    fun getTestJunitReportDir(): String?

    @JsonIgnore
    fun getActuatorUrl(): String?

    @JsonIgnore
    fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfigV1V2Common

    @JsonIgnore
    fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean

    @JsonIgnore
    fun getStubGenerative(): Boolean

    @JsonIgnore
    fun getStubDelayInMilliseconds(): Long?

    @JsonIgnore
    fun getStubDictionary(): String?

    @JsonIgnore
    fun getStubStrictMode(): Boolean?

    @JsonIgnore
    fun getStubFilter(): String?

    @JsonIgnore
    fun getStubHttpsConfiguration(): HttpsConfiguration?

    @JsonIgnore
    fun getStubGracefulRestartTimeoutInMilliseconds(): Long?

    @JsonIgnore
    fun getDefaultBaseUrl(): String

    @JsonIgnore
    fun getCustomImplicitStubBase(): String?

    @JsonIgnore
    fun getIgnoreInlineExamples(): Boolean

    @JsonIgnore
    fun getIgnoreInlineExampleWarnings(): Boolean

    @JsonIgnore
    fun getAllPatternsMandatory(): Boolean

    @JsonIgnore
    fun getSchemaExampleDefault(): Boolean

    @JsonIgnore
    fun getFuzzyMatchingEnabled(): Boolean

    @JsonIgnore
    fun getExtensibleQueryParams(): Boolean

    @JsonIgnore
    fun getEscapeSoapAction(): Boolean

    @JsonIgnore
    fun getPrettyPrint(): Boolean

    @JsonIgnore
    fun getAdditionalExampleParamsFilePath(): String?

    @JsonIgnore
    fun getHooks(): Map<String, String>

    @JsonIgnore
    fun getProxyConfig(): ProxyConfig?

    @JsonIgnore
    fun getDefaultPatternValues(): Map<String, Any>
    fun getVersion(): SpecmaticConfigVersion

    @JsonIgnore
    fun getMatchBranchEnabled(): Boolean

    @JsonIgnore
    fun mapSources(transform: (Source) -> Source): SpecmaticConfigV1V2Common

    @JsonIgnore
    fun getAuth(): Auth?

    @JsonIgnore
    fun getAuthBearerFile(): String?

    @JsonIgnore
    fun getAuthBearerEnvironmentVariable(): String?

    @JsonIgnore
    fun getAuthPersonalAccessToken(): String?

    @JsonIgnore
    fun getExamples(): List<String>

    @JsonIgnore
    fun getRepositoryProvider(): String?

    @JsonIgnore
    fun getRepositoryCollectionName(): String?

    @JsonIgnore
    fun getBackwardCompatibilityConfig(): BackwardCompatibilityConfig?

    @JsonIgnore
    fun getMcpConfiguration(): McpConfiguration?

    @JsonIgnore
    fun getPipelineProvider(): PipelineProvider?

    @JsonIgnore
    fun getPipelineDefinitionId(): Int?

    @JsonIgnore
    fun getPipelineOrganization(): String?

    @JsonIgnore
    fun getPipelineProject(): String?

    @JsonIgnore
    fun getOpenAPISecurityConfigurationScheme(scheme: String): SecuritySchemeConfiguration?

    @JsonIgnore
    fun getBasicAuthSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String?

    @JsonIgnore
    fun getBearerSecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String?

    @JsonIgnore
    fun getApiKeySecurityToken(
        schemeName: String,
        securitySchemeConfiguration: SecuritySchemeConfiguration?
    ): String?

    @JsonIgnore
    fun getVirtualServiceHost(): String?

    @JsonIgnore
    fun getVirtualServicePort(): Int?

    @JsonIgnore
    fun getVirtualServiceSpecs(): List<String>?

    @JsonIgnore
    fun getVirtualServiceSpecsDirPath(): String?

    @JsonIgnore
    fun getVirtualServiceLogsDirPath(): String?

    @JsonIgnore
    fun getVirtualServiceLogMode(): VirtualServiceConfiguration.VSLogMode?

    @JsonIgnore
    fun getVirtualServiceNonPatchableKeys(): Set<String>

    @JsonIgnore
    fun stubContracts(relativeTo: File = File(".")): List<String>
    fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfigV1V2Common
    fun getEnvironment(envName: String): JSONObjectValue
    fun enableResiliencyTests(): SpecmaticConfigV1V2Common
    fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfigV1V2Common
    fun withTestFilter(filter: String? = null): SpecmaticConfigV1V2Common
    fun withTestTimeout(timeoutInMilliseconds: Long? = null): SpecmaticConfigV1V2Common
    fun withStubModes(strictMode: Boolean? = null): SpecmaticConfigV1V2Common
    fun withStubFilter(filter: String? = null): SpecmaticConfigV1V2Common
    fun withGlobalMockDelay(delayInMilliseconds: Long): SpecmaticConfigV1V2Common

    @JsonIgnore
    fun testSpecPathFromConfigFor(absoluteSpecPath: String): String?

    @JsonIgnore
    fun stubSpecPathFromConfigFor(absoluteSpecPath: String): String?

    @JsonIgnore
    fun getLicensePath(): Path?

    @JsonIgnore
    fun getReportDirPath(suffix: String? = null): Path
    fun plusExamples(exampleDirectories: List<String>): SpecmaticConfig

    // TODO: REVIEW
    fun getSecurityConfiguration(): SecurityConfiguration?

    companion object {
        operator fun invoke(): SpecmaticConfig = SpecmaticConfigV1V2Common()
    }
}

fun SpecmaticConfig?.orDefault(): SpecmaticConfig = this ?: SpecmaticConfigV1V2Common()
