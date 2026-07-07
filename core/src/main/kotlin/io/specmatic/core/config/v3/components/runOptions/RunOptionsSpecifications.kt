package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import kotlin.String

interface IRunOptionSpecification {
    fun getId(): String?
    fun getServerOrigin(defaultHost: String): ServerOrigin?
    fun getConfig(): Map<String, Any>
    fun getOverlayFilePath(): String?
    fun isNoOpOverride(): Boolean

    @JsonIgnore
    fun extractServerOriginFromMap(map: Map<*, *>?, defaultHost: String): ServerOrigin? {
        val host = map?.get("host")?.toString() ?: defaultHost
        val port = map?.get("port")?.toString()?.toIntOrNull() ?: return null
        return ServerOrigin.from(host = host, port = port)
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
    override fun getServerOrigin(defaultHost: String): ServerOrigin? {
        if (spec.port == null) return extractServerOriginFromMap(spec.config["inMemoryBroker"] as? Map<*, *>, defaultHost)
        return ServerOrigin.from(host = spec.host ?: defaultHost, port = spec.port)
    }

    @JsonIgnore
    override fun getConfig(): Map<String, Any> {
        return buildMap {
            putAll(spec.config)
            spec.host?.let { put("host", it) }
            spec.port?.let { put("port", it) }
        }
    }

    @JsonIgnore
    override fun isNoOpOverride(): Boolean {
        return spec.host == null && spec.port == null && spec.overlayFilePath == null && spec.config.isEmpty()
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
    override fun isNoOpOverride(): Boolean {
        return spec.baseUrl == null && spec.host == null && spec.port == null
    }

    @JsonIgnore
    override fun getServerOrigin(defaultHost: String): ServerOrigin? {
        if (spec.baseUrl != null) return ServerOrigin.from(spec.baseUrl)
        if (spec.port == null) return null
        return ServerOrigin.from("http", spec.host ?: defaultHost, spec.port)
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
    override fun isNoOpOverride(): Boolean {
        return spec.baseUrl == null && spec.host == null && spec.port == null && spec.overlayFilePath == null && spec.securitySchemes == null
    }

    @JsonIgnore
    fun getSecuritySchemes(): Map<String, SecuritySchemeConfigurationV3>? {
        return spec.securitySchemes
    }

    @JsonIgnore
    override fun getServerOrigin(defaultHost: String): ServerOrigin? {
        if (spec.baseUrl != null) return ServerOrigin.from(spec.baseUrl)
        if (spec.port == null) return null
        return ServerOrigin.from("http", spec.host ?: defaultHost, spec.port)
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

data class OpenApiTestRunOptionsSpecifications(val spec: Value) : IRunOptionSpecification {
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
    override fun isNoOpOverride(): Boolean {
        return spec.baseUrl == null &&
            spec.host == null &&
            spec.port == null &&
            spec.overlayFilePath == null &&
            spec.securitySchemes == null &&
            spec.swaggerUrl == null
    }

    @JsonIgnore
    fun getSecuritySchemes(): Map<String, SecuritySchemeConfigurationV3>? {
        return spec.securitySchemes
    }

    @JsonIgnore
    override fun getServerOrigin(defaultHost: String): ServerOrigin? {
        if (spec.baseUrl != null) return ServerOrigin.from(spec.baseUrl)
        if (spec.port == null) return null
        return ServerOrigin.from("http", spec.host ?: defaultHost, spec.port)
    }

    data class Value(
        val id: String? = null,
        val baseUrl: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val overlayFilePath: String? = null,
        val securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null,
        val swaggerUrl: String? = null,
    )
}
