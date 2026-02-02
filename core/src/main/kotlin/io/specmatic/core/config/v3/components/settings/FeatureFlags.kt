package io.specmatic.core.config.v3.components.settings

data class FeatureFlags(
    val fuzzyMatcherForPayloads: Boolean? = null,
    val schemaExampleDefault: Boolean? = null,
    val escapeSoapAction: Boolean? = null
)
