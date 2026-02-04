package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.config.Auth
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.ReportConfigurationDetails
import io.specmatic.core.config.SecurityConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.config.StubConfiguration
import io.specmatic.core.config.TestConfiguration
import io.specmatic.core.config.VirtualServiceConfiguration
import io.specmatic.core.config.WorkflowConfiguration
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.nio.file.Path

data class SpecmaticConfigV2(
    val version: SpecmaticConfigVersion,
    val contracts: List<ContractConfig> = emptyList(),
    val auth: Auth? = null,
    val pipeline: Pipeline? = null,
    val environments: Map<String, Environment>? = null,
    val hooks: Map<String, String> = emptyMap(),
    val proxy: ProxyConfig? = null,
    val repository: RepositoryInfo? = null,
    val report: ReportConfigurationDetails? = null,
    val security: SecurityConfiguration? = null,
    val test: TestConfiguration? = null,
    val stub: StubConfiguration? = null,
    private val backwardCompatibility: BackwardCompatibilityConfig? = null,
    @field:JsonAlias("virtual_service")
    val virtualService: VirtualServiceConfiguration? = null,
    val examples: List<String>? = getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList(),
    val workflow: WorkflowConfiguration? = null,
    val ignoreInlineExamples: Boolean? = null,
    val ignoreInlineExampleWarnings: Boolean? = getBooleanValue(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS),
    val schemaExampleDefault: Boolean? = null,
    val fuzzy: Boolean? = null,
    val extensibleQueryParams: Boolean? = null,
    val escapeSoapAction: Boolean? = null,
    val prettyPrint: Boolean? = null,
    val additionalExampleParamsFilePath: String? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE),
    @field:JsonAlias("attribute_selection_pattern")
    val attributeSelectionPattern: AttributeSelectionPattern? = null,
    @field:JsonAlias("all_patterns_mandatory")
    val allPatternsMandatory: Boolean? = null,
    @field:JsonAlias("default_pattern_values")
    val defaultPatternValues: Map<String, Any> = emptyMap(),
    @field:JsonAlias("disable_telemetry")
    val disableTelemetry: Boolean? = null,
    private val logging: LoggingConfiguration? = null,
    private val mcp: McpConfiguration? = null,
    @field:JsonAlias("license_path")
    val licensePath: Path? = null,
    @field:JsonAlias("report_dir_path")
    val reportDirPath: Path? = null,
    private val globalSettings: SpecmaticGlobalSettings? = null,
) : SpecmaticVersionedConfig {
    override fun transform(): SpecmaticConfigV2Impl {
        return SpecmaticConfigV2Impl(
            version = currentConfigVersion(),
            sources = this.contracts.map { contract -> contract.transform() },
            auth = this.auth,
            pipeline = this.pipeline,
            environments = this.environments,
            hooks = this.hooks,
            proxy = this.proxy,
            repository = this.repository,
            report = this.report?.validatePresenceOfExcludedEndpoints(version),
            security = this.security,
            test = this.test,
            stub = this.stub,
            backwardCompatibility = this.backwardCompatibility,
            virtualService = this.virtualService,
            examples = this.examples,
            workflow = this.workflow,
            ignoreInlineExamples = this.ignoreInlineExamples,
            ignoreInlineExampleWarnings = this.ignoreInlineExampleWarnings,
            schemaExampleDefault = this.schemaExampleDefault,
            fuzzy = this.fuzzy,
            extensibleQueryParams = this.extensibleQueryParams,
            escapeSoapAction = this.escapeSoapAction,
            prettyPrint = this.prettyPrint,
            additionalExampleParamsFilePath = this.additionalExampleParamsFilePath,
            attributeSelectionPattern = this.attributeSelectionPattern,
            allPatternsMandatory = this.allPatternsMandatory,
            defaultPatternValues = this.defaultPatternValues,
            disableTelemetry = this.disableTelemetry,
            logging = this.logging,
            mcp = this.mcp,
            licensePath = this.licensePath,
            reportDirPath = this.reportDirPath,
            globalSettings = this.globalSettings
        )
    }

    companion object : SpecmaticVersionedConfigLoader {
        private fun currentConfigVersion(): SpecmaticConfigVersion {
            return SpecmaticConfigVersion.VERSION_2
        }

        override fun loadFrom(config: SpecmaticConfig): SpecmaticConfigV2 {
            require(config is SpecmaticConfigV2Impl) { "Should never convert the config to V2 from a higher version" }
            return SpecmaticConfigV2(
                version = currentConfigVersion(),
                contracts = config.sources.map { ContractConfig(it) },
                auth = config.getAuth(),
                pipeline = config.pipeline,
                environments = config.environments,
                hooks = config.getHooks(),
                proxy = config.proxy,
                repository = config.repository,
                report = config.report?.validatePresenceOfExcludedEndpoints(currentConfigVersion()),
                security = config.security,
                test = config.test,
                stub = config.stub,
                backwardCompatibility = config.backwardCompatibility,
                virtualService = config.virtualService,
                examples = config.getExamples(),
                workflow = config.workflow,
                ignoreInlineExamples = config.ignoreInlineExamples,
                ignoreInlineExampleWarnings = config.ignoreInlineExampleWarnings,
                schemaExampleDefault = config.schemaExampleDefault,
                fuzzy = config.fuzzy,
                extensibleQueryParams = config.extensibleQueryParams,
                escapeSoapAction = config.escapeSoapAction,
                prettyPrint = config.prettyPrint,
                additionalExampleParamsFilePath = config.additionalExampleParamsFilePath,
                attributeSelectionPattern = config.attributeSelectionPattern,
                allPatternsMandatory = config.allPatternsMandatory,
                defaultPatternValues = config.defaultPatternValues,
                disableTelemetry = config.disableTelemetry,
                logging = config.logging,
                mcp = config.mcp,
                licensePath = config.getLicensePath(),
                reportDirPath = config.reportDirPath,
                globalSettings = config.globalSettings
            )
        }
    }
}
