package io.specmatic.core.config.v3

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.CommonServiceConfig
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.Examples
import io.specmatic.core.config.v3.components.runOptions.ContextDependentRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptions
import io.specmatic.core.config.v3.components.sources.SourceV3

data class Components(
    val sources: Map<String, SourceV3>? = null,
    val services: Map<String, CommonServiceConfig<ContextDependentRunOptions, ContextDependentSettings>>? = null,
    val runOptions: Map<String, RunOptions>? = null,
    val examples: Examples? = null,
    val dictionaries: Map<String, Dictionary>? = null,
    val adapters: Map<String, Adapter>? = null,
    val certificates: Map<String, HttpsConfiguration>? = null,
    val settings: Map<String, Settings>? = null,
)
