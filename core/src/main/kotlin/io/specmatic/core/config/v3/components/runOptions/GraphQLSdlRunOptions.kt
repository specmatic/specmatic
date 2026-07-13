package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.config.v3.ServerOrigin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(GraphQLSdlTestConfig::class, name = "test"), JsonSubTypes.Type(GraphQLSdlMockConfig::class, name = "mock"))
sealed interface GraphQLSdlRunOptions : IRunOptions {
    val type: RunOptionType?

    @JsonIgnore
    override fun gerServerOrigin(): ServerOrigin? {
        val defaultHost = if (this is GraphQLSdlTestConfig) "localhost" else "0.0.0.0"
        return extractServerOriginFromMap(config, defaultHost)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class GraphQLSdlTestConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null
) : GraphQLSdlRunOptions {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for GraphQLSdlTestConfig, expected '${RunOptionType.TEST.value}'"
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
data class GraphQLSdlMockConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null
) : GraphQLSdlRunOptions {
    private val _config: MutableMap<String, Any> = linkedMapOf()

    init {
        require(type == null || type == RunOptionType.MOCK) {
            "Invalid type '$type' for GraphQLSdlMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}
