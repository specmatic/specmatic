package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.*
import io.specmatic.core.config.BackwardCompatibilityConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticGlobalSettings
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.resolve
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.resolveFullyOrNull
import io.specmatic.core.config.v3.resolveMapValuesOrEmpty
import io.specmatic.core.config.v3.resolveMapValuesOrNull
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrap
import io.specmatic.core.config.v3.wrapFullyOrNull
import io.specmatic.core.config.v3.wrapOrNull
import io.specmatic.core.config.v3.wrapValuesFullyOrNull
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.io.File
import java.nio.file.Path

data class SpecmaticConfigV2(
    val version: TemplateOrValue<SpecmaticConfigVersion>,
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
    val backwardCompatibility: TemplateOrValue<BackwardCompatibilityConfig>? = null,
    @field:JsonAlias("virtual_service")
    val virtualService: TemplateOrValue<VirtualServiceConfiguration>? = null,
    val examples: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val workflow: TemplateOrValue<WorkflowConfiguration>? = null,
    val ignoreInlineExamples: TemplateOrValue<Boolean>? = null,
    val ignoreInlineExampleWarnings: TemplateOrValue<Boolean>? = null,
    val schemaExampleDefault: TemplateOrValue<Boolean>? = null,
    val fuzzy: TemplateOrValue<Boolean>? = null,
    val extensibleQueryParams: TemplateOrValue<Boolean>? = null,
    val escapeSoapAction: TemplateOrValue<Boolean>? = null,
    val prettyPrint: TemplateOrValue<Boolean>? = null,
    val additionalExampleParamsFilePath: TemplateOrValue<String>? = null,
    @field:JsonAlias("attribute_selection_pattern")
    val attributeSelectionPattern: TemplateOrValue<AttributeSelectionPattern>? = null,
    @field:JsonAlias("all_patterns_mandatory")
    val allPatternsMandatory: TemplateOrValue<Boolean>? = null,
    @field:JsonAlias("default_pattern_values")
    val defaultPatternValues: TemplateOrValue<Map<String, TemplateOrValue<Any>>>? = null,
    @field:JsonAlias("disable_telemetry")
    val disableTelemetry: TemplateOrValue<Boolean>? = null,
    val logging: TemplateOrValue<LoggingConfiguration>? = null,
    val mcp: TemplateOrValue<McpConfiguration>? = null,
    @field:JsonAlias("license_path")
    val licensePath: TemplateOrValue<Path>? = null,
    @field:JsonAlias("report_dir_path")
    val reportDirPath: TemplateOrValue<Path>? = null,
    val globalSettings: TemplateOrValue<SpecmaticGlobalSettings>? = null,
) : SpecmaticVersionedConfig {
    @get:JsonIgnore
    val resolvedVersion: SpecmaticConfigVersion
        get() = version.resolve()

    @get:JsonIgnore
    val resolvedContracts: List<ContractConfig>
        get() = contracts.resolveFullyOrEmpty()

    @get:JsonIgnore
    val resolvedAuth: Auth?
        get() = auth.resolveOrNull()

    @get:JsonIgnore
    val resolvedPipeline: Pipeline?
        get() = pipeline.resolveOrNull()

    @get:JsonIgnore
    val resolvedEnvironments: Map<String, Environment>?
        get() = environments.resolveMapValuesOrNull()

    @get:JsonIgnore
    val resolvedHooks: Map<String, String>
        get() = hooks.resolveMapValuesOrEmpty()

    @get:JsonIgnore
    val resolvedProxy: ProxyConfig?
        get() = proxy.resolveOrNull()

    @get:JsonIgnore
    val resolvedRepository: RepositoryInfo?
        get() = repository.resolveOrNull()

    @get:JsonIgnore
    val resolvedReport: ReportConfigurationDetails?
        get() = report.resolveOrNull()

    @get:JsonIgnore
    val resolvedSecurity: SecurityConfiguration?
        get() = security.resolveOrNull()

    @get:JsonIgnore
    val resolvedTest: TestConfiguration?
        get() = test.resolveOrNull()

    @get:JsonIgnore
    val resolvedStub: StubConfiguration?
        get() = stub.resolveOrNull()

    @get:JsonIgnore
    val resolvedBackwardCompatibility: BackwardCompatibilityConfig?
        get() = backwardCompatibility.resolveOrNull()

    @get:JsonIgnore
    val resolvedVirtualService: VirtualServiceConfiguration?
        get() = virtualService.resolveOrNull()

    @get:JsonIgnore
    val resolvedExamples: List<String>
        get() = examples.resolveFullyOrNull() ?: (getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList())

    @get:JsonIgnore
    val resolvedWorkflow: WorkflowConfiguration?
        get() = workflow.resolveOrNull()

    @get:JsonIgnore
    val resolvedIgnoreInlineExamples: Boolean?
        get() = ignoreInlineExamples.resolveOrNull()

    @get:JsonIgnore
    val resolvedIgnoreInlineExampleWarnings: Boolean?
        get() = ignoreInlineExampleWarnings.resolveOrNull() ?: Flags.getBooleanValueOrNull(Flags.IGNORE_INLINE_EXAMPLE_WARNINGS)

    @get:JsonIgnore
    val resolvedSchemaExampleDefault: Boolean?
        get() = schemaExampleDefault.resolveOrNull()

    @get:JsonIgnore
    val resolvedFuzzy: Boolean?
        get() = fuzzy.resolveOrNull()

    @get:JsonIgnore
    val resolvedExtensibleQueryParams: Boolean?
        get() = extensibleQueryParams.resolveOrNull()

    @get:JsonIgnore
    val resolvedEscapeSoapAction: Boolean?
        get() = escapeSoapAction.resolveOrNull()

    @get:JsonIgnore
    val resolvedPrettyPrint: Boolean?
        get() = prettyPrint.resolveOrNull()

    @get:JsonIgnore
    val resolvedAdditionalExampleParamsFilePath: String?
        get() = additionalExampleParamsFilePath.resolveOrNull() ?: getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)

    @get:JsonIgnore
    val resolvedAttributeSelectionPattern: AttributeSelectionPattern?
        get() = attributeSelectionPattern.resolveOrNull()

    @get:JsonIgnore
    val resolvedAllPatternsMandatory: Boolean?
        get() = allPatternsMandatory.resolveOrNull()

    @get:JsonIgnore
    val resolvedDefaultPatternValues: Map<String, Any>
        get() = defaultPatternValues.resolveMapValuesOrEmpty()

    @get:JsonIgnore
    val resolvedDisableTelemetry: Boolean?
        get() = disableTelemetry.resolveOrNull()

    @get:JsonIgnore
    val resolvedLogging: LoggingConfiguration?
        get() = logging.resolveOrNull()

    @get:JsonIgnore
    val resolvedMcp: McpConfiguration?
        get() = mcp.resolveOrNull()

    @get:JsonIgnore
    val resolvedLicensePath: Path?
        get() = licensePath.resolveOrNull()

    @get:JsonIgnore
    val resolvedReportDirPath: Path?
        get() = reportDirPath.resolveOrNull()

    @get:JsonIgnore
    val resolvedGlobalSettings: SpecmaticGlobalSettings?
        get() = globalSettings.resolveOrNull()

    override fun transform(file: File?): SpecmaticConfigV1V2Common {
        return SpecmaticConfigV1V2Common(
            version = currentConfigVersion().wrap(),
            sources = this.resolvedContracts.map { contract -> contract.transform() }.wrapFullyOrNull(),
            auth = this.auth,
            pipeline = this.pipeline,
            environments = this.environments,
            hooks = this.hooks,
            proxy = this.proxy,
            repository = this.repository,
            report = this.resolvedReport?.validatePresenceOfExcludedEndpoints(resolvedVersion).wrapOrNull(),
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
                version = currentConfigVersion().wrap(),
                contracts = config.resolvedSources.map { ContractConfig(it) }.wrapFullyOrNull(),
                auth = config.auth,
                pipeline = config.pipeline,
                environments = config.environments,
                hooks = config.hooks,
                proxy = config.proxy,
                repository = config.repository,
                report = config.report?.resolveOrNull()?.validatePresenceOfExcludedEndpoints(currentConfigVersion()).wrapOrNull(),
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
                licensePath = config.licensePath,
                reportDirPath = config.reportDirPath,
            )
        }
    }
}
