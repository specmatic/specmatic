package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.ExampleTemplateStringDeserializer

data class GeneralSettings(
    val disableTelemetry: Boolean? = null,
    val ignoreInlineExamples: Boolean? = null,
    val ignoreInlineExampleWarnings: Boolean? = null,
    val prettyPrint: Boolean? = null,
    val logging: LoggingConfiguration? = null,
    val featureFlags: FeatureFlags? = null,
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    private val specExamplesDirectoryTemplate: String? = null,
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    private val sharedExamplesDirectoryTemplate: List<String>? = null
)
