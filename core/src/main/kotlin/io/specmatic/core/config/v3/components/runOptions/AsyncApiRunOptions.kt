package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(AsyncApiTestConfig::class, name = "test"), JsonSubTypes.Type(AsyncApiMockConfig::class, name = "mock"))
sealed interface AsyncApiRunOptions : IRunOptions {
    val type: RunOptionType?

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        val servers = config["servers"] as? List<*> ?: return null
        val server = servers.firstOrNull() as? Map<*, *> ?: return null
        return extractBaseUrlFromMap(server)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiTestConfig(
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf()
) : AsyncApiRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    fun withConfig(newConfig: Map<String, Any>): AsyncApiTestConfig = copy(_config = LinkedHashMap(newConfig))

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
data class AsyncApiMockConfig(
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf()
) : AsyncApiRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    fun withConfig(newConfig: Map<String, Any>): AsyncApiMockConfig = copy(_config = LinkedHashMap(newConfig))

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
