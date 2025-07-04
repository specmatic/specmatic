package io.specmatic.core.config.v1

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue

data class SpecmaticConfigV1 (
	@field:JsonAlias("contract_repositories")
	val sources: List<Source> = emptyList(),
	val auth: Auth? = null,
	val pipeline: Pipeline? = null,
	val environments: Map<String, Environment>? = null,
	val hooks: Map<String, String> = emptyMap(),
	val repository: RepositoryInfo? = null,
	val report: ReportConfigurationDetails? = null,
	val security: SecurityConfigurationDetails? = null,
	val test: TestConfiguration? = TestConfiguration(),
	val stub: StubConfiguration = StubConfiguration(),
	@field:JsonAlias("virtual_service")
	val virtualService: VirtualServiceConfiguration = VirtualServiceConfiguration(),
	val examples: List<String> = getStringValue(EXAMPLE_DIRECTORIES)?.split(",") ?: emptyList(),
	val workflow: WorkflowConfiguration? = null,
	val ignoreInlineExamples: Boolean? = null,
	val additionalExampleParamsFilePath: String? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE),
	@field:JsonAlias("attribute_selection_pattern")
	val attributeSelectionPattern: AttributeSelectionPattern = AttributeSelectionPattern(),
	@field:JsonAlias("all_patterns_mandatory")
	val allPatternsMandatory: Boolean? = null,
	@field:JsonAlias("default_pattern_values")
	val defaultPatternValues: Map<String, Any> = emptyMap(),
	val version: SpecmaticConfigVersion? = null
): SpecmaticVersionedConfig {
	override fun transform(): SpecmaticConfig {
		return SpecmaticConfig(
			sources = this.sources,
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
			defaultPatternValues = this.defaultPatternValues,
			version = SpecmaticConfigVersion.VERSION_1
		)
	}

	companion object : SpecmaticVersionedConfigLoader {
		override fun loadFrom(config: SpecmaticConfig): SpecmaticVersionedConfig {
			throw UnsupportedOperationException("Should never convert the config to V1")
		}
	}
}