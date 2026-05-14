package io.specmatic.core.config.v1

import com.fasterxml.jackson.annotation.JsonAlias
import io.specmatic.core.*
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.wrap
import io.specmatic.core.config.wrapFully
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXAMPLE_DIRECTORIES
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import java.io.File

data class SpecmaticConfigV1 (
	@field:JsonAlias("contract_repositories")
	val sources: TemplateOrValue<List<TemplateOrValue<Source>>>? = null,
	val auth: TemplateOrValue<Auth>? = null,
	val pipeline: TemplateOrValue<Pipeline>? = null,
	val environments: TemplateOrValue<Map<String, TemplateOrValue<Environment>>>? = null,
	val hooks: TemplateOrValue<Map<String, TemplateOrValue<String>>>? = null,
	val repository: TemplateOrValue<RepositoryInfo>? = null,
	val report: TemplateOrValue<ReportConfigurationDetails>? = null,
	val security: TemplateOrValue<SecurityConfiguration>? = null,
	val test: TemplateOrValue<TestConfiguration>? = null,
	val stub: TemplateOrValue<StubConfiguration>? = null,
	@field:JsonAlias("virtual_service")
	val virtualService: TemplateOrValue<VirtualServiceConfiguration>? = null,
	val examples: TemplateOrValue<List<TemplateOrValue<String>>>? = getStringValue(EXAMPLE_DIRECTORIES)?.split(",")?.wrapFully(),
	val workflow: TemplateOrValue<WorkflowConfiguration>? = null,
	val ignoreInlineExamples: TemplateOrValue<Boolean>? = null,
	val additionalExampleParamsFilePath: TemplateOrValue<String>? = getStringValue(Flags.ADDITIONAL_EXAMPLE_PARAMS_FILE)?.let(::wrap),
	@field:JsonAlias("attribute_selection_pattern")
	val attributeSelectionPattern: TemplateOrValue<AttributeSelectionPattern>? = null,
	@field:JsonAlias("all_patterns_mandatory")
	val allPatternsMandatory: TemplateOrValue<Boolean>? = null,
	@field:JsonAlias("default_pattern_values")
	val defaultPatternValues: TemplateOrValue<Map<String, TemplateOrValue<Any>>>? = null,
	val version: TemplateOrValue<SpecmaticConfigVersion>? = null,
	@field:JsonAlias("disable_telemetry")
	val disableTelemetry: TemplateOrValue<Boolean>? = null,
): SpecmaticVersionedConfig {
	override fun transform(file: File?): SpecmaticConfigV1V2Common {
		return SpecmaticConfigV1V2Common(
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
			version = version,
			disableTelemetry = this.disableTelemetry,
		)
	}

	companion object : SpecmaticVersionedConfigLoader {
		override fun loadFrom(config: SpecmaticConfig): SpecmaticVersionedConfig {
			throw UnsupportedOperationException("Should never convert the config to V1")
		}
	}
}