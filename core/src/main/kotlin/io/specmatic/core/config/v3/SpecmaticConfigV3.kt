package io.specmatic.core.config.v3

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.SpecmaticConfig.Companion.getPipeline
import io.specmatic.core.SpecmaticConfig.Companion.getRepository
import io.specmatic.core.SpecmaticConfig.Companion.getSecurityConfiguration
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue

data class SpecmaticConfigV3(
    val version: SpecmaticConfigVersion,
    val contracts: List<ContractConfigV2> = emptyList(),
    val auth: Auth? = null,
    val pipeline: Pipeline? = null,
    val environments: Map<String, Environment>? = null,
    val hooks: Map<String, String> = emptyMap(),
    val repository: RepositoryInfo? = null,
    val report: ReportConfiguration? = null,
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
    val defaultPatternValues: Map<String, Any> = emptyMap()
): SpecmaticVersionedConfig {
    override fun transform(): SpecmaticConfig {
        return SpecmaticConfig(
            version = SpecmaticConfigVersion.VERSION_3,
            sources = this.contracts.map { contract -> contract.transform() },
            auth = this.auth,
            pipeline = this.pipeline,
            environments = this.environments,
            hooks = this.hooks,
            repository = this.repository,
            report = this.report,
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

    companion object: SpecmaticVersionedConfigLoader {
        override fun loadFrom(config: SpecmaticConfig): SpecmaticVersionedConfig {
            return SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                contracts = config.sources.map { ContractConfigV2(it) },
                auth = config.getAuth(),
                pipeline = getPipeline(config),
                environments = config.environments,
                hooks = config.getHooks(),
                repository = getRepository(config),
                report = config.report,
                security = getSecurityConfiguration(config),
                test = SpecmaticConfig.getTestConfiguration(config),
                stub = SpecmaticConfig.getStubConfiguration(config),
                virtualService = SpecmaticConfig.getVirtualServiceConfiguration(config),
                examples = config.getExamples(),
                workflow = SpecmaticConfig.getWorkflowConfiguration(config),
                ignoreInlineExamples = SpecmaticConfig.getIgnoreInlineExamples(config),
                additionalExampleParamsFilePath = config.getAdditionalExampleParamsFilePath(),
                attributeSelectionPattern = SpecmaticConfig.getAttributeSelectionPattern(config),
                allPatternsMandatory = SpecmaticConfig.getAllPatternsMandatory(config),
                defaultPatternValues = config.getDefaultPatternValues()
            )
        }

    }
}