package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3

interface IRunOptionSpecification {
    fun getId(): String?
    fun getOverlayFilePath(): String?
}

data class RunOptionsSpecifications(val spec: Value) : IRunOptionSpecification {
    @JsonIgnore
    override fun getId(): String? {
        return spec.id
    }

    @JsonIgnore
    override fun getOverlayFilePath(): String? {
        return spec.overlayFilePath
    }

    data class Value(
        val id: String? = null,
        val overlayFilePath: String? = null,
    )
}

data class OpenApiRunOptionsSpecifications(val spec: Value) : IRunOptionSpecification {
    @JsonIgnore
    override fun getId(): String? {
        return spec.id
    }

    @JsonIgnore
    override fun getOverlayFilePath(): String? {
        return spec.overlayFilePath
    }

    @JsonIgnore
    fun getSecuritySchemes(): Map<String, SecuritySchemeConfigurationV3>? {
        return spec.securitySchemes
    }

    data class Value(
        val id: String? = null,
        val overlayFilePath: String? = null,
        val securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null
    )
}
