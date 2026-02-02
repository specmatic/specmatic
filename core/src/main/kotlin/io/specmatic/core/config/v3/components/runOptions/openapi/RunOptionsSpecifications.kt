package io.specmatic.core.config.v3.components.runOptions.openapi

import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3

data class OpenApiRunOptionsSpecifications(val spec: Value) {
    data class Value(
        val id: String? = null,
        val filter: String? = null,
        val overlayFilePath: String? = null,
        val securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null
    )
}
