package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.ConfigPathMapper
import java.io.File

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(ProtobufTestConfig::class, name = "test"), JsonSubTypes.Type(ProtobufMockConfig::class, name = "mock"))
sealed interface ProtobufRunOptions : IRunOptions {
    val type: RunOptionType?
    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ProtobufRunOptions

    @JsonIgnore
    override fun gerServerOrigin(): ServerOrigin? {
        val defaultHost = if (this is ProtobufTestConfig) "localhost" else "0.0.0.0"
        return extractServerOriginFromMap(config, defaultHost)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class ProtobufTestConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf()
) : ProtobufRunOptions {
    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ProtobufTestConfig = copy(
        _config = config.mapImportPaths(mapper, configDirectory).toMutableMap(),
        specs = specs?.mapIndexed { index, spec ->
            spec.mapPaths(mapper.child("specs").child(index), configDirectory)
        },
    )

    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for ProtobufTestConfig, expected '${RunOptionType.TEST.value}'"
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
data class ProtobufMockConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf()
) : ProtobufRunOptions {
    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ProtobufMockConfig = copy(
        _config = config.mapImportPaths(mapper, configDirectory).toMutableMap(),
        specs = specs?.mapIndexed { index, spec ->
            spec.mapPaths(mapper.child("specs").child(index), configDirectory)
        },
    )

    init {
        require(type == null || type == RunOptionType.MOCK) {
            "Invalid type '$type' for ProtobufMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}

private fun Map<String, Any>.mapImportPaths(mapper: ConfigPathMapper, base: File): Map<String, Any> = mapValues { (key, value) ->
    if (key != "importPaths") return@mapValues value
    if (value !is List<*>) return@mapValues value
    value.mapIndexed { index, item ->
        if (item !is String) return@mapIndexed item
        mapper.child("config").child("importPaths").child(index).map(path = item, baseDirectory = base)
    }
}
