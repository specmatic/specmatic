package io.specmatic.core.config.v3.components.runOptions.openapi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3

@JsonIgnoreProperties("id", "filter", "overlayFilePath")
interface IRunOptionSpecification {
    fun getId(): String?
    fun getFilter(): String?
    fun getOverlayFilePath(): String?
}

data class RunOptionsSpecifications(val spec: Value) : IRunOptionSpecification {
    override fun getId(): String? {
        return spec.id
    }

    override fun getFilter(): String? {
        return spec.filter
    }

    override fun getOverlayFilePath(): String? {
        return spec.overlayFilePath
    }

    data class Value(
        val id: String? = null,
        val filter: String? = null,
        val overlayFilePath: String? = null,
    )
}

data class OpenApiRunOptionsSpecifications(val spec: Value) : IRunOptionSpecification {
    override fun getId(): String? {
        return spec.id
    }

    override fun getFilter(): String? {
        return spec.filter
    }

    override fun getOverlayFilePath(): String? {
        return spec.overlayFilePath
    }

    data class Value(
        val id: String? = null,
        val filter: String? = null,
        val overlayFilePath: String? = null,
        val securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null
    )
}
