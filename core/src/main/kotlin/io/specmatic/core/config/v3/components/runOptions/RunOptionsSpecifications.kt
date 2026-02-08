package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3

interface IRunOptionSpecification {
    fun getId(): String?
    fun getOverlayFilePath(): String?
    fun getConfig(): Map<String, Any>
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

    @JsonIgnore
    override fun getConfig(): Map<String, Any> {
        return spec.config
    }

    data class Value(val id: String? = null, val overlayFilePath: String? = null) {
        private val _config: MutableMap<String, Any> = linkedMapOf()

        @get:JsonAnyGetter
        val config: Map<String, Any> get() = _config

        @JsonAnySetter
        fun put(key: String, value: Any) {
            _config[key] = value
        }
    }
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
    override fun getConfig(): Map<String, Any> {
        return emptyMap()
    }

    @JsonIgnore
    fun getSecuritySchemes(): Map<String, SecuritySchemeConfigurationV3>? {
        return spec.securitySchemes
    }

    @JsonIgnore
    fun getBaseUrl(): String? {
        return spec.baseUrl
    }

    data class Value(
        val id: String? = null,
        val baseUrl: String? = null,
        val overlayFilePath: String? = null,
        val securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null,
    )
}
