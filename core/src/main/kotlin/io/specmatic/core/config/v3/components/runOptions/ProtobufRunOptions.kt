package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(ProtobufTestConfig::class, name = "test"), JsonSubTypes.Type(ProtobufMockConfig::class, name = "mock"),)
sealed interface ProtobufRunOptions : IRunOptions { val config: Map<String, Any>?; val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class ProtobufTestConfig(override val baseUrl: String? = null, override val specs: List<RunOptionsSpecifications>? = null) : ProtobufRunOptions {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.TEST) {
            "Invalid type '$input' for ProtobufTestConfig, expected '${RunOptionType.TEST.value}'"
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
data class ProtobufMockConfig(override val baseUrl: String? = null, override val specs: List<RunOptionsSpecifications>? = null) : ProtobufRunOptions {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.MOCK) {
            "Invalid type '$input' for ProtobufMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}
