package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*
import io.specmatic.core.config.v3.TemplateOrValue

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(AsyncApiTestConfig::class, name = "test"), JsonSubTypes.Type(AsyncApiMockConfig::class, name = "mock"))
sealed interface AsyncApiRunOptions : IRunOptions {
    val type: RunOptionType?

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        val brokerConfig = config["inMemoryBroker"] as? Map<*, *> ?: return null
        return extractBaseUrlFromMap(brokerConfig, defaultHost = "localhost")
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiTestConfig(
    override val specs: TemplateOrValue<List<TemplateOrValue<RunOptionsSpecifications>>>? = null,
    @JsonIgnore private val _config: MutableMap<String, TemplateOrValue<Any>> = linkedMapOf()
) : AsyncApiRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    fun withConfig(newConfig: Map<String, TemplateOrValue<Any>>): AsyncApiTestConfig = copy(_config = LinkedHashMap(newConfig))

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
data class AsyncApiMockConfig(
    override val specs: TemplateOrValue<List<TemplateOrValue<RunOptionsSpecifications>>>? = null,
    @JsonIgnore private val _config: MutableMap<String, TemplateOrValue<Any>> = linkedMapOf()
) : AsyncApiRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    fun withConfig(newConfig: Map<String, TemplateOrValue<Any>>): AsyncApiMockConfig = copy(_config = LinkedHashMap(newConfig))

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
