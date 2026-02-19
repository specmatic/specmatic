package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getAllPatternsMandatory
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getAttributeSelectionConfigOrNull
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getPipeline
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getRepository
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getSecurityConfiguration
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getStubConfigOrNull
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getWorkflowConfiguration
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getTestConfigOrNull
import io.specmatic.core.SpecmaticConfigV1V2Common.Companion.getVirtualServiceConfigOrNull
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.io.File
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
    val examples: List<String> = getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList(),
    val workflow: WorkflowConfiguration? = null,
    val ignoreInlineExamples: Boolean? = null,
    val ignoreInlineExampleWarnings: Boolean? = Flags.getBooleanValueOrNull(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS),
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
    override fun transform(file: File?): SpecmaticConfigV1V2Common {
        return SpecmaticConfigV1V2Common(
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
            config as? SpecmaticConfigV1V2Common
                ?: throw ContractException("Expected v1 or v2 config format, but got an incompatible config structure.")

            return SpecmaticConfigV2(
                version = currentConfigVersion(),
                contracts = SpecmaticConfigV1V2Common.getSources(config).map { ContractConfig(it) },
                auth = config.getAuth(),
                pipeline = getPipeline(config),
                environments = SpecmaticConfigV1V2Common.getEnvironments(config),
                hooks = config.getHooks(),
                proxy = config.getProxyConfig(),
                repository = getRepository(config),
                report = SpecmaticConfigV1V2Common.getReport(config)?.validatePresenceOfExcludedEndpoints(currentConfigVersion()),
                security = getSecurityConfiguration(config),
                test = getTestConfigOrNull(config),
                stub = getStubConfigOrNull(config),
                virtualService = getVirtualServiceConfigOrNull(config),
                examples = config.getExamples(),
                workflow = getWorkflowConfiguration(config),
                ignoreInlineExamples = SpecmaticConfigV1V2Common.getIgnoreInlineExamples(config),
                ignoreInlineExampleWarnings = SpecmaticConfigV1V2Common.getIgnoreInlineExampleWarningsOrNull(config),
                schemaExampleDefault = SpecmaticConfigV1V2Common.getSchemaExampleDefaultOrNull(config),
                fuzzy = SpecmaticConfigV1V2Common.getFuzzyMatchingEnabledOrNull(config),
                extensibleQueryParams = SpecmaticConfigV1V2Common.getExtensibleQueryParamsOrNull(config),
                escapeSoapAction = SpecmaticConfigV1V2Common.getEscapeSoapActionOrNull(config),
                prettyPrint = SpecmaticConfigV1V2Common.getPrettyPrintOrNull(config),
                additionalExampleParamsFilePath = config.getAdditionalExampleParamsFilePath(),
                attributeSelectionPattern = getAttributeSelectionConfigOrNull(config),
                allPatternsMandatory = getAllPatternsMandatory(config),
                defaultPatternValues = config.getDefaultPatternValues(),
                disableTelemetry = SpecmaticConfigV1V2Common.isTelemetryDisabledOrNull(config),
                licensePath = config.getLicensePath(),
                reportDirPath = SpecmaticConfigV1V2Common.getReportDirPathOrNull(config),
            )
        }
    }
}
