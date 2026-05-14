package io.specmatic.core.config.v3.components.settings

import io.specmatic.core.config.v3.TemplateOrValue

data class FeatureFlags(
    val fuzzyMatcherForPayloads: TemplateOrValue<Boolean>? = null,
    val schemaExampleDefault: TemplateOrValue<Boolean>? = null,
    val escapeSoapAction: TemplateOrValue<Boolean>? = null
)
