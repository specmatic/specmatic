package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.ExampleTemplateStringDeserializer
import java.io.File

data class GeneralSettings(
    val disableTelemetry: Boolean? = null,
    val ignoreInlineExamples: Boolean? = null,
    val ignoreInlineExampleWarnings: Boolean? = null,
    val prettyPrint: Boolean? = null,
    val logging: LoggingConfiguration? = null,
    val featureFlags: FeatureFlags? = null,
    @field:JsonDeserialize(using = ExampleTemplateStringDeserializer::class)
    val specExamplesDirectoryTemplate: String? = null,
    @field:JsonDeserialize(contentUsing = ExampleTemplateStringDeserializer::class)
    val sharedExamplesDirectoryTemplate: List<String>? = null
) {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): GeneralSettings = copy(
        logging = logging?.mapPaths(mapper.child("logging"), configDirectory),
    )
}
