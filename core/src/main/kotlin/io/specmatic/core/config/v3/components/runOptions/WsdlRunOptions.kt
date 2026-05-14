package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.TemplateOrValue

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(WsdlTestConfig::class, name = "test"), JsonSubTypes.Type(WsdlMockConfig::class, name = "mock"))
sealed interface WsdlRunOptions : IRunOptions { val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class WsdlTestConfig(
    val baseUrl: TemplateOrValue<String>? = null,
    val host: TemplateOrValue<String>? = null,
    val port: TemplateOrValue<Int>? = null,
    override val cert: TemplateOrValue<RefOrValue<HttpsConfiguration>>? = null,
    override val specs: TemplateOrValue<List<TemplateOrValue<WsdlRunOptionsSpecifications>>>? = null
) : WsdlRunOptions, ConfigWithCert {
    private val _config: MutableMap<String, TemplateOrValue<Any>> = linkedMapOf()

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        if (baseUrl != null) return baseUrl
        if (port != null) return "http://${host ?: "localhost"}:$port"
        return null
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
    override val config: Map<String, TemplateOrValue<Any>> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: TemplateOrValue<Any>) {
        _config[key] = value
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class WsdlMockConfig(
    val baseUrl: TemplateOrValue<String>? = null,
    val host: TemplateOrValue<String>? = null,
    val port: TemplateOrValue<Int>? = null,
    override val cert: TemplateOrValue<RefOrValue<HttpsConfiguration>>? = null,
    override val specs: TemplateOrValue<List<TemplateOrValue<WsdlRunOptionsSpecifications>>>? = null
) : WsdlRunOptions, ConfigWithCert {
    private val _config: MutableMap<String, TemplateOrValue<Any>> = linkedMapOf()

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        if (baseUrl != null) return baseUrl
        if (port == null) return null
        val scheme = if (cert == null) "http" else "https"
        return "$scheme://${host ?: "0.0.0.0"}:$port"
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
    override val config: Map<String, TemplateOrValue<Any>> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: TemplateOrValue<Any>) {
        _config[key] = value
    }
}
