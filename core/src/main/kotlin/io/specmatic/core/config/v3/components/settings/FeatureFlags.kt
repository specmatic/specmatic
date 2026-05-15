package io.specmatic.core.config.v3.components.settings

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

data class FeatureFlags(
    val fuzzyMatcherForPayloads: TemplateOrValue<Boolean>? = null,
    val schemaExampleDefault: TemplateOrValue<Boolean>? = null,
    val escapeSoapAction: TemplateOrValue<Boolean>? = null
) {
    @JsonIgnore
    fun getFuzzyMatcherForPayloads(): Boolean? {
        return fuzzyMatcherForPayloads?.resolve()
    }

    @JsonIgnore
    fun getSchemaExampleDefault(): Boolean? {
        return schemaExampleDefault?.resolve()
    }

    @JsonIgnore
    fun getEscapeSoapAction(): Boolean? {
        return escapeSoapAction?.resolve()
    }
}
