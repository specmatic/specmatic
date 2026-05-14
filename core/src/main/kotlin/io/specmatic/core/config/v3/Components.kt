package io.specmatic.core.config.v3

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.Examples
import io.specmatic.core.config.v3.components.runOptions.ContextDependentRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptions
import io.specmatic.core.config.v3.components.sources.SourceV3

data class Components(
    val sources: Map<String, TemplateOrValue<SourceV3>>? = null,
    val services: Map<String, TemplateOrValue<CommonServiceConfig<ContextDependentRunOptions, ContextDependentSettings>>>? = null,
    val runOptions: Map<String, TemplateOrValue<RunOptions>>? = null,
    val examples: TemplateOrValue<Examples>? = null,
    val dictionaries: Map<String, TemplateOrValue<Dictionary>>? = null,
    val adapters: Map<String, TemplateOrValue<Adapter>>? = null,
    val certificates: Map<String, TemplateOrValue<HttpsConfiguration>>? = null,
    val settings: Map<String, TemplateOrValue<Settings>>? = null,
)
