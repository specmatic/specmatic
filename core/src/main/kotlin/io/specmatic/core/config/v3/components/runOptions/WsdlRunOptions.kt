package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.ServerOrigin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(WsdlTestConfig::class, name = "test"), JsonSubTypes.Type(WsdlMockConfig::class, name = "mock"))
sealed interface WsdlRunOptions : IRunOptions { val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class WsdlTestConfig(
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    override val cert: RefOrValue<HttpsConfiguration>? = null,
    override val specs: List<WsdlRunOptionsSpecifications>? = null
) : WsdlRunOptions, ConfigWithCert {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    @JsonIgnore
    override fun gerServerOrigin(): ServerOrigin? {
        if (baseUrl != null) return ServerOrigin.from(baseUrl)
        if (port == null) return null
        return ServerOrigin.from("http", host ?: "localhost", port)
    }

    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.TEST) {
            "Invalid type '$input' for AsyncApiTestConfig, expected '${RunOptionType.TEST.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class WsdlMockConfig(
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    override val cert: RefOrValue<HttpsConfiguration>? = null,
    override val specs: List<WsdlRunOptionsSpecifications>? = null
) : WsdlRunOptions, ConfigWithCert {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    @JsonIgnore
    override fun gerServerOrigin(): ServerOrigin? {
        if (baseUrl != null) return ServerOrigin.from(baseUrl)
        if (port == null) return null
        val scheme = if (cert == null) "http" else "https"
        return ServerOrigin.from(scheme, host ?: "0.0.0.0", port)
    }

    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.MOCK) {
            "Invalid type '$input' for AsyncApiMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}
