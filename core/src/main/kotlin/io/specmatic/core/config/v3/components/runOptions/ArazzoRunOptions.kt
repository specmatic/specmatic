package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.v3.ServerOrigin
import java.io.File

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(ArazzoTestConfig::class, name = "test"), JsonSubTypes.Type(ArazzoMockConfig::class, name = "mock"))
sealed interface ArazzoRunOptions : IRunOptions {
    val type: RunOptionType?

    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ArazzoRunOptions

    @JsonIgnore
    override fun gerServerOrigin(): ServerOrigin? = null
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class ArazzoTestConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf(),
) : ArazzoRunOptions {
    fun withConfig(newConfig: Map<String, Any>): ArazzoTestConfig = copy(_config = LinkedHashMap(newConfig))

    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ArazzoTestConfig = copy(
        specs = specs?.mapIndexed { index, spec ->
            spec.mapPaths(mapper.child("specs").child(index), configDirectory)
        },
    )

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }

    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for ArazzoTestConfig, expected '${RunOptionType.TEST.value}'"
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class ArazzoMockConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf(),
) : ArazzoRunOptions {
    fun withConfig(newConfig: Map<String, Any>): ArazzoMockConfig = copy(_config = LinkedHashMap(newConfig))

    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ArazzoMockConfig = copy(
        specs = specs?.mapIndexed { index, spec ->
            spec.mapPaths(mapper.child("specs").child(index), configDirectory)
        },
    )

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }

    init {
        require(type == null || type == RunOptionType.MOCK) {
            "Invalid type '$type' for ArazzoMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }
}
