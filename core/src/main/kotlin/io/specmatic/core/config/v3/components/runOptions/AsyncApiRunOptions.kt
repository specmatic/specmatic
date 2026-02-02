package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(AsyncApiTestConfig::class, name = "test"), JsonSubTypes.Type(AsyncApiMockConfig::class, name = "mock"),)
sealed interface AsyncApiRunOptions { val config: Map<String, Any?>?; val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiTestConfig(@get:JsonAnyGetter override val config: Map<String, Any?>? = null) : AsyncApiRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.TEST) {
            "Invalid type '$input' for AsyncApiTestConfig, expected '${RunOptionType.TEST.value}'"
        }
    }

    companion object {
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        @JvmStatic
        fun create(@JsonAnySetter config: Map<String, Any?>? = null): AsyncApiTestConfig = AsyncApiTestConfig(config)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiMockConfig(@get:JsonAnyGetter override val config: Map<String, Any?>? = null) : AsyncApiRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.MOCK) {
            "Invalid type '$input' for AsyncApiMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    companion object {
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        @JvmStatic
        fun create(@JsonAnySetter config: Map<String, Any?>? = null): AsyncApiMockConfig = AsyncApiMockConfig(config)
    }
}
