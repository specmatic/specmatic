package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.ExampleTemplateStringDeserializer
import io.specmatic.core.config.v3.TemplateOrValue

data class GeneralSettings(
    val disableTelemetry: TemplateOrValue<Boolean>? = null,
    val ignoreInlineExamples: TemplateOrValue<Boolean>? = null,
    val ignoreInlineExampleWarnings: TemplateOrValue<Boolean>? = null,
    val prettyPrint: TemplateOrValue<Boolean>? = null,
    val logging: TemplateOrValue<LoggingConfiguration>? = null,
    val featureFlags: TemplateOrValue<FeatureFlags>? = null,
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    val specExamplesDirectoryTemplate: TemplateOrValue<String>? = null,
    @field:JsonDeserialize(contentUsing = ExampleTemplateStringDeserializer::class)
    val sharedExamplesDirectoryTemplate: TemplateOrValue<List<TemplateOrValue<String>>>? = null
)
