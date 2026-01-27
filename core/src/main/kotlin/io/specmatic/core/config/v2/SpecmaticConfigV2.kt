package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.SpecmaticConfig.Companion.getAllPatternsMandatory
import io.specmatic.core.SpecmaticConfig.Companion.getAttributeSelectionConfigOrNull
import io.specmatic.core.SpecmaticConfig.Companion.getPipeline
import io.specmatic.core.SpecmaticConfig.Companion.getRepository
import io.specmatic.core.SpecmaticConfig.Companion.getSecurityConfiguration
import io.specmatic.core.SpecmaticConfig.Companion.getStubConfigOrNull
import io.specmatic.core.SpecmaticConfig.Companion.getWorkflowConfiguration
import io.specmatic.core.SpecmaticConfig.Companion.getTestConfigOrNull
import io.specmatic.core.SpecmaticConfig.Companion.getVirtualServiceConfigOrNull
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getBooleanValue
import io.specmatic.core.utilities.Flags.Companion.getStringValue

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
    @field:JsonAlias("virtual_service")
    val virtualService: VirtualServiceConfiguration? = null,
    val examples: List<String> = getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList(),
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
) : SpecmaticVersionedConfig {
    override fun transform(): SpecmaticConfig {
        return SpecmaticConfig(
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
            disableTelemetry = this.disableTelemetry
        )
    }

    companion object : SpecmaticVersionedConfigLoader {
        private fun currentConfigVersion(): SpecmaticConfigVersion {
            return SpecmaticConfigVersion.VERSION_2
        }

        override fun loadFrom(config: SpecmaticConfig): SpecmaticConfigV2 {
            return SpecmaticConfigV2(
                version = currentConfigVersion(),
                contracts = SpecmaticConfig.getSources(config).map { ContractConfig(it) },
                auth = config.getAuth(),
                pipeline = getPipeline(config),
                environments = SpecmaticConfig.getEnvironments(config),
                hooks = config.getHooks(),
                proxy = config.getProxyConfig(),
                repository = getRepository(config),
                report = SpecmaticConfig.getReport(config)?.validatePresenceOfExcludedEndpoints(currentConfigVersion()),
                security = getSecurityConfiguration(config),
                test = getTestConfigOrNull(config),
                stub = getStubConfigOrNull(config),
                virtualService = getVirtualServiceConfigOrNull(config),
                examples = config.getExamples(),
                workflow = getWorkflowConfiguration(config),
                ignoreInlineExamples = SpecmaticConfig.getIgnoreInlineExamples(config),
                ignoreInlineExampleWarnings = config.getIgnoreInlineExampleWarnings(),
                schemaExampleDefault = config.getSchemaExampleDefault(),
                fuzzy = config.getFuzzyMatchingEnabled(),
                extensibleQueryParams = config.getExtensibleQueryParams(),
                escapeSoapAction = config.getEscapeSoapAction(),
                prettyPrint = config.getPrettyPrint(),
                additionalExampleParamsFilePath = config.getAdditionalExampleParamsFilePath(),
                attributeSelectionPattern = getAttributeSelectionConfigOrNull(config),
                allPatternsMandatory = getAllPatternsMandatory(config),
                defaultPatternValues = config.getDefaultPatternValues()
            )
        }
    }
}
