package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import kotlin.String

interface IRunOptionSpecification {
    fun getId(): String?
    fun getBaseUrl(defaultHost: String): String?
    fun getConfig(): Map<String, Any>
    fun getOverlayFilePath(): String?

    fun extractBaseUrlFromMap(map: Map<*, *>?, defaultHost: String): String? {
        if (map.isNullOrEmpty()) return null
        val host = map["host"]?.toString() ?: defaultHost
        val port = map["port"]?.toString()?.toIntOrNull() ?: return null
        return "$host:$port"
    }
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
    override fun getBaseUrl(defaultHost: String): String? {
        if (spec.port == null) return extractBaseUrlFromMap(spec.config["inMemoryBroker"] as? Map<*, *>, "localhost")
        return "${spec.host ?: defaultHost}:${spec.port}"
    }

    @JsonIgnore
    override fun getConfig(): Map<String, Any> {
        return buildMap {
            putAll(spec.config)
            spec.host?.let { put("host", it) }
            spec.port?.let { put("port", it) }
        }
    }

    data class Value(
        val id: String? = null,
        val overlayFilePath: String? = null,
        val host: String? = null,
        val port: Int? = null,
        @JsonIgnore
        private val _config: MutableMap<String, Any> = linkedMapOf()
    ) {
        @get:JsonAnyGetter
        val config: Map<String, Any> get() = _config.toMap()

        @JsonAnySetter
        fun put(key: String, value: Any) {
            _config[key] = value
        }

        fun withConfig(newConfig: Map<String, Any>): Value = copy(_config = LinkedHashMap(newConfig))
    }
}

data class WsdlRunOptionsSpecifications(val spec: Value) : IRunOptionSpecification {
    @JsonIgnore
    override fun getId(): String? {
        return spec.id
    }

    @JsonIgnore
    override fun getOverlayFilePath(): String? {
        return null
    }

    @JsonIgnore
    override fun getConfig(): Map<String, Any> {
        return emptyMap()
    }

    @JsonIgnore
    override fun getBaseUrl(defaultHost: String): String? {
        if (spec.baseUrl != null) return spec.baseUrl
        if (spec.port == null) return null
        return "http://${spec.host ?: defaultHost}:${spec.port}"
    }

    data class Value(
        val id: String? = null,
        val baseUrl: String? = null,
        val host: String? = null,
        val port: Int? = null,
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
    override fun getConfig(): Map<String, Any> {
        return emptyMap()
    }

    @JsonIgnore
    fun getSecuritySchemes(): Map<String, SecuritySchemeConfigurationV3>? {
        return spec.securitySchemes
    }

    @JsonIgnore
    override fun getBaseUrl(defaultHost: String): String? {
        if (spec.baseUrl != null) return spec.baseUrl
        if (spec.port == null) return null
        return "http://${spec.host ?: defaultHost}:${spec.port}"
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
