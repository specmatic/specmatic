package io.specmatic.core.config.v2

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.SpecmaticConfig.Companion.getAllPatternsMandatory
import io.specmatic.core.SpecmaticConfig.Companion.getAttributeSelectionPattern
import io.specmatic.core.SpecmaticConfig.Companion.getPipeline
import io.specmatic.core.SpecmaticConfig.Companion.getRepository
import io.specmatic.core.SpecmaticConfig.Companion.getSecurityConfiguration
import io.specmatic.core.SpecmaticConfig.Companion.getStubConfiguration
import io.specmatic.core.SpecmaticConfig.Companion.getTestConfiguration
import io.specmatic.core.SpecmaticConfig.Companion.getVirtualServiceConfiguration
import io.specmatic.core.SpecmaticConfig.Companion.getWorkflowConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue

data class SpecmaticConfigV2(
    val version: SpecmaticConfigVersion,
    val contracts: List<ContractConfig> = emptyList(),
    val auth: Auth? = null,
    val pipeline: Pipeline? = null,
    val environments: Map<String, Environment>? = null,
    val hooks: Map<String, String> = emptyMap(),
    val repository: RepositoryInfo? = null,
    val report: ReportConfigurationDetails? = null,
    val security: SecurityConfiguration? = null,
    val test: TestConfiguration? = TestConfiguration(),
    val stub: StubConfiguration = StubConfiguration(),
    @field:JsonAlias("virtual_service") val virtualService: VirtualServiceConfiguration = VirtualServiceConfiguration(),
    val examples: List<String> = getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList(),
    val workflow: WorkflowConfiguration? = null,
    val ignoreInlineExamples: Boolean? = null,
    val additionalExampleParamsFilePath: String? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE),
    @field:JsonAlias("attribute_selection_pattern")
    val attributeSelectionPattern: AttributeSelectionPattern = AttributeSelectionPattern(),
    @field:JsonAlias("all_patterns_mandatory")
    val allPatternsMandatory: Boolean? = null,
    @field:JsonAlias("default_pattern_values")
    val defaultPatternValues: Map<String, Any>? = null
) : SpecmaticVersionedConfig {
    override fun transform(): SpecmaticConfig {
        return SpecmaticConfig(
            version = currentConfigVersion(),
            sources = this.contracts.map { contract -> contract.transform() },
            auth = this.auth,
            pipeline = this.pipeline,
            environments = this.environments,
            hooks = this.hooks,
            repository = this.repository,
            report = this.report?.validatePresenceOfExcludedEndpoints(version),
            security = this.security,
            test = this.test,
            stub = this.stub,
            virtualService = this.virtualService,
            examples = this.examples,
            workflow = this.workflow,
            ignoreInlineExamples = this.ignoreInlineExamples,
            additionalExampleParamsFilePath = this.additionalExampleParamsFilePath,
            attributeSelectionPattern = this.attributeSelectionPattern,
            allPatternsMandatory = this.allPatternsMandatory,
            defaultPatternValues = this.defaultPatternValues
        )
    }

    companion object : SpecmaticVersionedConfigLoader {
        private fun currentConfigVersion(): SpecmaticConfigVersion {
            return SpecmaticConfigVersion.VERSION_2
        }

        override fun loadFrom(config: SpecmaticConfig): SpecmaticVersionedConfig {
            return SpecmaticConfigV2(
                version = currentConfigVersion(),
                contracts = SpecmaticConfig.getSources(config).map { ContractConfig(it) },
                auth = config.getAuth(),
                pipeline = getPipeline(config),
                environments = SpecmaticConfig.getEnvironments(config),
                hooks = config.getHooks(),
                repository = getRepository(config),
                report = SpecmaticConfig.getReport(config)?.validatePresenceOfExcludedEndpoints(currentConfigVersion()),
                security = getSecurityConfiguration(config),
                test = getTestConfiguration(config),
                stub = getStubConfiguration(config),
                virtualService = getVirtualServiceConfiguration(config),
                examples = config.getExamples(),
                workflow = getWorkflowConfiguration(config),
                ignoreInlineExamples = SpecmaticConfig.getIgnoreInlineExamples(config),
                additionalExampleParamsFilePath = config.getAdditionalExampleParamsFilePath(),
                attributeSelectionPattern = getAttributeSelectionPattern(config),
                allPatternsMandatory = getAllPatternsMandatory(config),
                defaultPatternValues = config.getDefaultPatternValues()
            )
        }
    }
}