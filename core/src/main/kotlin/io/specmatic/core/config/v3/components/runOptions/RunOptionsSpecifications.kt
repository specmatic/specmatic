package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.specmatic.core.config.resolveFully
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import kotlin.String

interface IRunOptionSpecification {
    fun getId(): String?
    fun getBaseUrl(defaultHost: String): String?
    fun getConfig(): Map<String, TemplateOrValue<Any>>
    fun getOverlayFilePath(): String?
    fun isNoOpOverride(): Boolean

    fun extractBaseUrlFromMap(map: Map<*, *>?, defaultHost: String): String? {
        if (map.isNullOrEmpty()) return null
        val host = map["host"]?.resolveIfTemplateOrValue()?.toString() ?: defaultHost
        val port = map["port"]?.resolveIfTemplateOrValue()?.toString()?.toIntOrNull() ?: return null
        return "$host:$port"
    }

    private fun Any.resolveIfTemplateOrValue(): Any {
        return when (this) {
            is TemplateOrValue<*> -> this.resolve()
            else -> this
        }
    }
}

data class RunOptionsSpecifications(val spec: TemplateOrValue<Value>) : IRunOptionSpecification {
    @JsonIgnore
    override fun getId(): String? {
        return spec.resolve().id?.resolve()
    }

    @JsonIgnore
    override fun getOverlayFilePath(): String? {
        return spec.resolve().overlayFilePath?.resolve()
    }

    @JsonIgnore
    override fun getBaseUrl(defaultHost: String): String? {
        val spec = spec.resolve()
        if (spec.port == null) return extractBaseUrlFromMap(spec.config["inMemoryBroker"] as? Map<*, *>, "localhost")
        return "${spec.host?.resolve() ?: defaultHost}:${spec.port.resolve()}"
    }

    @JsonIgnore
    override fun getConfig(): Map<String, TemplateOrValue<Any>> {
        val spec = spec.resolve()
        return buildMap {
            putAll(spec.config)
            spec.host?.let { put("host", it) }
            spec.port?.let { put("port", it) }
        }
    }

    @JsonIgnore
    override fun isNoOpOverride(): Boolean {
        val spec = spec.resolve()
        return spec.host == null && spec.port == null && spec.overlayFilePath == null && spec.config.isEmpty()
    }

    data class Value(
        val id: TemplateOrValue<String>? = null,
        val overlayFilePath: TemplateOrValue<String>? = null,
        val host: TemplateOrValue<String>? = null,
        val port: TemplateOrValue<Int>? = null,
        @JsonIgnore
        private val _config: MutableMap<String, TemplateOrValue<Any>> = linkedMapOf()
    ) {
        @get:JsonAnyGetter
        val config: Map<String, TemplateOrValue<Any>> get() = _config.toMap()

        @JsonAnySetter
        fun put(key: String, value: TemplateOrValue<Any>) {
            _config[key] = value
        }

        fun withConfig(newConfig: Map<String, TemplateOrValue<Any>>): Value = copy(_config = LinkedHashMap(newConfig))
    }
}

data class WsdlRunOptionsSpecifications(val spec: TemplateOrValue<Value>) : IRunOptionSpecification {
    @JsonIgnore
    override fun getId(): String? {
        return spec.resolve().id?.resolve()
    }

    @JsonIgnore
    override fun getOverlayFilePath(): String? {
        return null
    }

    @JsonIgnore
    override fun getConfig(): Map<String, TemplateOrValue<Any>> {
        return emptyMap()
    }

    @JsonIgnore
    override fun isNoOpOverride(): Boolean {
        val spec = spec.resolve()
        return spec.baseUrl == null && spec.host == null && spec.port == null
    }

    @JsonIgnore
    override fun getBaseUrl(defaultHost: String): String? {
        val spec = spec.resolve()
        val baseUrl = spec.baseUrl?.resolve()
        val host = spec.host?.resolve()
        val port = spec.port?.resolve()

        if (baseUrl != null) return baseUrl
        if (port == null) return null
        return "http://${host ?: defaultHost}:${port}"
    }

    data class Value(
        val id: TemplateOrValue<String>? = null,
        val baseUrl: TemplateOrValue<String>? = null,
        val host: TemplateOrValue<String>? = null,
        val port: TemplateOrValue<Int>? = null,
    )
}

data class OpenApiRunOptionsSpecifications(val spec: TemplateOrValue<Value>) : IRunOptionSpecification {
    @JsonIgnore
    override fun getId(): String? {
        return spec.resolve().id?.resolve()
    }

    @JsonIgnore
    override fun getOverlayFilePath(): String? {
        return spec.resolve().overlayFilePath?.resolve()
    }

    @JsonIgnore
    override fun getConfig(): Map<String, TemplateOrValue<Any>> {
        return emptyMap()
    }

    @JsonIgnore
    override fun isNoOpOverride(): Boolean {
        val spec = spec.resolve()
        return spec.baseUrl == null && spec.host == null && spec.port == null && spec.overlayFilePath == null && spec.securitySchemes == null
    }

    @JsonIgnore
    fun getSecuritySchemes(): Map<String, SecuritySchemeConfigurationV3>? {
        return spec.resolve().securitySchemes?.resolveFully()
    }

    @JsonIgnore
    override fun getBaseUrl(defaultHost: String): String? {
        val spec = spec.resolve()
        val baseUrl = spec.baseUrl?.resolve()
        if (baseUrl != null) return baseUrl

        val host = spec.host?.resolve()
        val port = spec.port?.resolve() ?: return null
        return "http://${host ?: defaultHost}:${port}"
    }

    data class Value(
        val id: TemplateOrValue<String>? = null,
        val baseUrl: TemplateOrValue<String>? = null,
        val host: TemplateOrValue<String>? = null,
        val port: TemplateOrValue<Int>? = null,
        val overlayFilePath: TemplateOrValue<String>? = null,
        val securitySchemes: TemplateOrValue<Map<String, TemplateOrValue<SecuritySchemeConfigurationV3>>>? = null,
    )
}
