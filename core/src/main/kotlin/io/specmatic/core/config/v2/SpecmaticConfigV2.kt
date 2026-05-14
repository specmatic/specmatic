package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.wrap
import io.specmatic.core.config.wrapFully
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.io.File
import java.nio.file.Path

data class SpecmaticConfigV2(
    val version: TemplateOrValue<SpecmaticConfigVersion> = wrap(SpecmaticConfigVersion.VERSION_2),
    val contracts: TemplateOrValue<List<TemplateOrValue<ContractConfig>>>? = null,
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
    private val backwardCompatibility: TemplateOrValue<BackwardCompatibilityConfig>? = null,
    @field:JsonAlias("virtual_service")
    val virtualService: TemplateOrValue<VirtualServiceConfiguration>? = null,
    val examples: TemplateOrValue<List<TemplateOrValue<String>>>? = getStringValue(EXAMPLE_DIRECTORIES)?.split(",")?.wrapFully(),
    val workflow: TemplateOrValue<WorkflowConfiguration>? = null,
    val ignoreInlineExamples: TemplateOrValue<Boolean>? = null,
    val ignoreInlineExampleWarnings: TemplateOrValue<Boolean>? = Flags.getBooleanValueOrNull(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS)?.let(::wrap),
    val schemaExampleDefault: TemplateOrValue<Boolean>? = null,
    val fuzzy: TemplateOrValue<Boolean>? = null,
    val extensibleQueryParams: TemplateOrValue<Boolean>? = null,
    val escapeSoapAction: TemplateOrValue<Boolean>? = null,
    val prettyPrint: TemplateOrValue<Boolean>? = null,
    val additionalExampleParamsFilePath: TemplateOrValue<String>? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)?.let(::wrap),
    @field:JsonAlias("attribute_selection_pattern")
    val attributeSelectionPattern: TemplateOrValue<AttributeSelectionPattern>? = null,
    @field:JsonAlias("all_patterns_mandatory")
    val allPatternsMandatory: TemplateOrValue<Boolean>? = null,
    @field:JsonAlias("default_pattern_values")
    val defaultPatternValues: TemplateOrValue<Map<String, TemplateOrValue<Any>>>? = null,
    @field:JsonAlias("disable_telemetry")
    val disableTelemetry: TemplateOrValue<Boolean>? = null,
    private val logging: TemplateOrValue<LoggingConfiguration>? = null,
    private val mcp: TemplateOrValue<McpConfiguration>? = null,
    @field:JsonAlias("license_path")
    val licensePath: TemplateOrValue<Path>? = null,
    @field:JsonAlias("report_dir_path")
    val reportDirPath: TemplateOrValue<Path>? = null,
    private val globalSettings: TemplateOrValue<SpecmaticGlobalSettings>? = null,
) : SpecmaticVersionedConfig {
    override fun transform(file: File?): SpecmaticConfigV1V2Common {
        this.report?.resolve()?.validatePresenceOfExcludedEndpoints(this.version.resolve())
        return SpecmaticConfigV1V2Common(
            version = this.version,
            sources = this.contracts?.resolveFully()?.map { contract -> contract.transform() }?.wrapFully(),
            auth = this.auth,
            pipeline = this.pipeline,
            environments = this.environments,
            hooks = this.hooks,
            proxy = this.proxy,
            repository = this.repository,
            report = this.report,
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
                version = wrap(currentConfigVersion()),
                contracts = config.sources?.resolveFully()?.map { ContractConfig(it) }?.wrapFully(),
                auth = config.auth,
                pipeline = config.pipeline,
                environments = config.environments,
                hooks = config.hooks,
                proxy = config.proxy,
                repository = config.repository,
                report = config.report,
                security = config.security,
                test = config.test,
                stub = config.stub,
                virtualService = config.virtualService,
                examples = config.examples,
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
                licensePath = config.licensePath,
                reportDirPath = config.reportDirPath,
                globalSettings = config.globalSettings,
            )
        }
    }
}
