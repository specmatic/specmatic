package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(JsonSubTypes.Type(GraphQLSdlTestConfig::class, name = "test"), JsonSubTypes.Type(GraphQLSdlMockConfig::class, name = "mock"),)
sealed interface GraphQLSdlRunOptions { val config: Map<String, Any?>?; val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class GraphQLSdlTestConfig(@get:JsonAnyGetter override val config: Map<String, Any?>? = null) : GraphQLSdlRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.TEST) {
            "Invalid type '$input' for GraphQLSdlTestConfig, expected '${RunOptionType.TEST.value}'"
        }
    }

    companion object {
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        @JvmStatic
        fun create(@JsonAnySetter config: Map<String, Any?>? = null): GraphQLSdlTestConfig = GraphQLSdlTestConfig(config)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class GraphQLSdlMockConfig(@get:JsonAnyGetter override val config: Map<String, Any?>? = null) : GraphQLSdlRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.MOCK) {
            "Invalid type '$input' for GraphQLSdlMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    companion object {
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        @JvmStatic
        fun create(@JsonAnySetter config: Map<String, Any?>? = null): GraphQLSdlMockConfig = GraphQLSdlMockConfig(config)
    }
}
