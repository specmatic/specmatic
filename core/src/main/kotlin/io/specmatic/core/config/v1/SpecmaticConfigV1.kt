package io.specmatic.core.config.v1

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.config.v3.wrap
import io.specmatic.core.config.v3.wrapFullyOrNull
import io.specmatic.core.config.v3.wrapOrNull
import io.specmatic.core.config.v3.wrapValuesFullyOrNull
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.io.File

data class SpecmaticConfigV1 (
	@field:JsonAlias("contract_repositories")
	val sources: List<Source> = emptyList(),
	val auth: Auth? = null,
	val pipeline: Pipeline? = null,
	val environments: Map<String, Environment>? = null,
	val hooks: Map<String, String> = emptyMap(),
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
	val additionalExampleParamsFilePath: String? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE),
	@field:JsonAlias("attribute_selection_pattern")
	val attributeSelectionPattern: AttributeSelectionPattern? = null,
	@field:JsonAlias("all_patterns_mandatory")
	val allPatternsMandatory: Boolean? = null,
	@field:JsonAlias("default_pattern_values")
	val defaultPatternValues: Map<String, Any> = emptyMap(),
	val version: SpecmaticConfigVersion? = null,
	@field:JsonAlias("disable_telemetry")
	val disableTelemetry: Boolean? = null,
): SpecmaticVersionedConfig {
	override fun transform(file: File?): SpecmaticConfigV1V2Common {
		return SpecmaticConfigV1V2Common(
			sources = this.sources.wrapFullyOrNull(),
			auth = this.auth.wrapOrNull(),
			pipeline = this.pipeline.wrapOrNull(),
			environments = this.environments.wrapValuesFullyOrNull(),
			hooks = this.hooks.wrapValuesFullyOrNull(),
			repository = this.repository.wrapOrNull(),
			report = this.report.wrapOrNull(),
			security = this.security.wrapOrNull(),
			test = this.test.wrapOrNull(),
			stub = this.stub.wrapOrNull(),
			virtualService = this.virtualService.wrapOrNull(),
			examples = this.examples.wrapFullyOrNull(),
			workflow = this.workflow.wrapOrNull(),
			ignoreInlineExamples = this.ignoreInlineExamples.wrapOrNull(),
			additionalExampleParamsFilePath = this.additionalExampleParamsFilePath.wrapOrNull(),
			attributeSelectionPattern = this.attributeSelectionPattern.wrapOrNull(),
			allPatternsMandatory = this.allPatternsMandatory.wrapOrNull(),
			defaultPatternValues = this.defaultPatternValues.wrapValuesFullyOrNull(),
			version = SpecmaticConfigVersion.VERSION_1.wrap(),
			disableTelemetry = this.disableTelemetry.wrapOrNull(),
		)
	}

	companion object : SpecmaticVersionedConfigLoader {
		override fun loadFrom(config: SpecmaticConfig): SpecmaticVersionedConfig {
			throw UnsupportedOperationException("Should never convert the config to V1")
		}
	}
}
