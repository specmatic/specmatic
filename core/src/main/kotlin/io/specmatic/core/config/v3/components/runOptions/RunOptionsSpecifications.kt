package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import kotlin.String

interface IRunOptionSpecification {
    fun getId(): String?
    fun getBaseUrl(): String?
    fun getConfig(): Map<String, Any>
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

    @JsonIgnore
    override fun getBaseUrl(): String? {
        if (spec.host == null || spec.port == null) return null
        return "${spec.host}:${spec.port}"
    }

    @JsonIgnore
    override fun getConfig(): Map<String, Any> {
        return buildMap {
            putAll(spec.config)
            spec.host?.let { put("host", it) }
            spec.port?.let { put("port", it) }
        }
    }

    data class Value(val id: String? = null, val overlayFilePath: String? = null, val host: String? = null, val port: Int? = null) {
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
    override fun getBaseUrl(): String? {
        if (spec.baseUrl != null) return spec.baseUrl
        if (spec.host == null || spec.port == null) return null
        return "http://${spec.host}:${spec.port}"
    }

    data class Value(
        val id: String? = null,
        val baseUrl: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val overlayFilePath: String? = null,
        val securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null,
    )
}
