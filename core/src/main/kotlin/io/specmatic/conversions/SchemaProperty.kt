package io.specmatic.conversions

import io.specmatic.core.pattern.Pattern

data class SchemaProperty(
    val extensions: Map<String, Any>,
    val properties: Map<String, Pattern>
)
