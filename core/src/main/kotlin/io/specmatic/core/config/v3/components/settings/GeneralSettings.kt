package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.config.ExampleTemplateStringDeserializer
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

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
) {
    @JsonIgnore
    fun getDisableTelemetry(): Boolean? {
        return disableTelemetry?.resolve()
    }

    @JsonIgnore
    fun getIgnoreInlineExamples(): Boolean? {
        return ignoreInlineExamples?.resolve()
    }

    @JsonIgnore
    fun getIgnoreInlineExampleWarnings(): Boolean? {
        return ignoreInlineExampleWarnings?.resolve()
    }

    @JsonIgnore
    fun getPrettyPrint(): Boolean? {
        return prettyPrint?.resolve()
    }

    @JsonIgnore
    fun getLogging(): LoggingConfiguration? {
        return logging?.resolve()
    }

    @JsonIgnore
    fun getFeatureFlags(): FeatureFlags? {
        return featureFlags?.resolve()
    }

    @JsonIgnore
    fun getSpecExamplesDirectoryTemplate(): String? {
        return specExamplesDirectoryTemplate?.resolve()
    }

    @JsonIgnore
    fun getSharedExamplesDirectoryTemplate(): List<String>? {
        return sharedExamplesDirectoryTemplate?.resolveFully()
    }
}
