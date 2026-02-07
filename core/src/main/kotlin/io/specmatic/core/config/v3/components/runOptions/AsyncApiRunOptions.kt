package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(AsyncApiTestConfig::class, name = "test"), JsonSubTypes.Type(AsyncApiMockConfig::class, name = "mock"),)
sealed interface AsyncApiRunOptions : IRunOptions { val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiTestConfig(override val baseUrl: String? = null, override val specs: List<RunOptionsSpecifications>? = null) : AsyncApiRunOptions {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.TEST) {
            "Invalid type '$input' for AsyncApiTestConfig, expected '${RunOptionType.TEST.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiMockConfig(override val baseUrl: String? = null, override val specs: List<RunOptionsSpecifications>? = null) : AsyncApiRunOptions {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.MOCK) {
            "Invalid type '$input' for AsyncApiMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}
