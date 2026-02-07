package io.specmatic.core

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.azure.AzureAPI
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.SpecmaticSpecConfig
import io.specmatic.core.config.Switch
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.utilities.ContractSource
import io.specmatic.core.utilities.ContractSourceEntry
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.reporter.ctrf.model.CtrfSpecConfig
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.isSameBaseIgnoringHost
import java.io.File
import java.net.URI
import java.nio.file.Path

data class SpecificationSource(
    val type: SourceProvider,
    val repository: String? = null,
    val directory: String? = null,
    val branch: String? = null,
    val matchBranch: Boolean? = null,
    val test: List<SpecificationSourceEntry> = emptyList(),
    val mock: List<SpecificationSourceEntry> = emptyList(),
)

data class SpecificationSourceEntry(
    val specFile: File,
    val specPathInConfig: String,
    val port: Int? = null,
    val baseUrl: String? = null,
    val resiliencyTestSuite: ResiliencyTestSuite? = null,
    val type: SourceProvider,
    val repository: String? = null,
    val directory: String? = null,
    val branch: String? = null,
    val matchBranch: Boolean? = null,
    val exampleDirs: List<String>? = null
) {
    fun toContractSourceEntry(): ContractSourceEntry {
        return ContractSourceEntry(specFile.canonicalPath, baseUrl, resiliencyTestSuite, exampleDirs)
    }

    constructor(source: Source, specFile: File, specPathInConfig: String, baseUrl: String? = null, port: Int? = null, resiliencyTestSuite: ResiliencyTestSuite? = null) : this(
        specFile = specFile,
        baseUrl = baseUrl,
        port = port,
        resiliencyTestSuite = resiliencyTestSuite,
        specPathInConfig = specPathInConfig,
        type = source.provider,
        repository = source.repository,
        directory = source.directory,
        branch = source.branch,
        matchBranch = source.matchBranch
    )
}

interface SpecmaticConfig {
    @JsonIgnore
    fun getLogConfigurationOrDefault(): LoggingConfiguration

    @JsonIgnore
    fun getSpecificationSources(): List<SpecificationSource>

    @JsonIgnore
    fun getFirstMockSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry?

    @JsonIgnore
    fun getFirstTestSourceMatching(predicate: (SpecificationSourceEntry) -> Boolean): SpecificationSourceEntry?

    @JsonIgnore
    fun isTelemetryDisabled(): Boolean

    @JsonIgnore
    fun testConfigFor(specPath: String, specType: String): SpecmaticSpecConfig?

    @JsonIgnore
    fun testConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig?

    @JsonIgnore
    fun stubConfigFor(file: File, specType: SpecType): SpecmaticSpecConfig?

    @JsonIgnore
    fun stubConfigFor(specPath: String, specType: String): SpecmaticSpecConfig?

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
    fun dropExcludedEndpointsAfterVersion1(latestVersion: SpecmaticConfigVersion): SpecmaticConfig

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
    fun stubBaseUrlPathAssociatedTo(url: String, defaultBaseUrl: String): String {
        val parsedUrl = URI(url)
        return stubBaseUrls(defaultBaseUrl).map(::URI).firstOrNull { stubBaseUrl ->
            isSameBaseIgnoringHost(parsedUrl, stubBaseUrl)
        }?.path.orEmpty()
    }

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
    fun isResiliencyTestingEnabled(): Boolean {
        return (getResiliencyTestsEnabled() != ResiliencyTestSuite.none)
    }

    @JsonIgnore
    fun isOnlyPositiveTestingEnabled(): Boolean {
        return (getResiliencyTestsEnabled() == ResiliencyTestSuite.positiveOnly)
    }

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
    fun getTestOverlayFilePath(specFile: File, specType: SpecType): String?

    @JsonIgnore
    fun getTestBaseUrl(specType: SpecType): String?

    @JsonIgnore
    fun getCoverageReportBaseUrl(specType: SpecType): String?

    @JsonIgnore
    fun getTestSwaggerUrl(): String?

    @JsonIgnore
    fun getTestSwaggerUIBaseUrl(): String?

    @JsonIgnore
    fun getTestJunitReportDir(): String?

    @JsonIgnore
    fun getActuatorUrl(): String?

    @JsonIgnore
    fun copyResiliencyTestsConfig(onlyPositive: Boolean): SpecmaticConfig

    @JsonIgnore
    fun getStubIncludeMandatoryAndRequestedKeysInResponse(): Boolean

    @JsonIgnore
    fun getStubGenerative(specFile: File?): Boolean

    @JsonIgnore
    fun getStubDelayInMilliseconds(specFile: File?): Long?

    @JsonIgnore
    fun getStubDictionary(specFile: File): String?

    @JsonIgnore
    fun getStubStrictMode(specFile: File?): Boolean?

    @JsonIgnore
    fun getStubFilter(specFile: File): String?

    @JsonIgnore
    fun getStubHttpsConfiguration(): CertRegistry

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
    fun getAuth(repositoryUrl: String): Auth?

    @JsonIgnore
    fun getAuthBearerFile(repositoryUrl: String): String?

    @JsonIgnore
    fun getAuthBearerEnvironmentVariable(repositoryUrl: String): String?

    @JsonIgnore
    fun getAuthPersonalAccessToken(repositoryUrl: String): String?

    @JsonIgnore
    fun getTestExampleDirs(specFile: File): List<String>

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
    fun getOpenAPISecurityConfigurationScheme(specFile: File, scheme: String): SecuritySchemeConfiguration?

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
    fun updateReportConfiguration(reportConfiguration: ReportConfiguration): SpecmaticConfig
    fun getEnvironment(envName: String): JSONObjectValue
    fun enableResiliencyTests(): SpecmaticConfig
    fun withTestModes(strictMode: Boolean?, lenientMode: Boolean): SpecmaticConfig
    fun withTestFilter(filter: String? = null): SpecmaticConfig
    fun withTestTimeout(timeoutInMilliseconds: Long? = null): SpecmaticConfig
    fun withStubModes(strictMode: Boolean? = null): SpecmaticConfig
    fun withStubFilter(filter: String? = null): SpecmaticConfig
    fun withGlobalMockDelay(delayInMilliseconds: Long): SpecmaticConfig
    fun withMatchBranch(matchBranch: Boolean): SpecmaticConfig

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
    fun getSecurityConfiguration(specFile: File): SecurityConfiguration?

    companion object {
        operator fun invoke(): SpecmaticConfig = SpecmaticConfigV1V2Common()
    }
}

fun SpecmaticConfig?.orDefault(): SpecmaticConfig = this ?: SpecmaticConfigV1V2Common()
