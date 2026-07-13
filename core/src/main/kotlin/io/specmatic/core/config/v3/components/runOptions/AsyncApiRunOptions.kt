package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.*
import io.specmatic.core.config.v3.ServerOrigin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(AsyncApiTestConfig::class, name = "test"), JsonSubTypes.Type(AsyncApiMockConfig::class, name = "mock"))
sealed interface AsyncApiRunOptions : IRunOptions {
    val type: RunOptionType?

    @JsonIgnore
    override fun gerServerOrigin(): ServerOrigin? {
        val brokerConfig = config["inMemoryBroker"] as? Map<*, *> ?: return null
        return extractServerOriginFromMap(brokerConfig, defaultHost = "localhost")
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class AsyncApiTestConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf()
) : AsyncApiRunOptions {
    fun withConfig(newConfig: Map<String, Any>): AsyncApiTestConfig = copy(_config = LinkedHashMap(newConfig))

    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for AsyncApiTestConfig, expected '${RunOptionType.TEST.value}'"
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
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf()
) : AsyncApiRunOptions {
    fun withConfig(newConfig: Map<String, Any>): AsyncApiMockConfig = copy(_config = LinkedHashMap(newConfig))

    init {
        require(type == null || type == RunOptionType.MOCK) {
            "Invalid type '$type' for AsyncApiMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }

    @get:JsonAnyGetter
    override val config: Map<String, Any> get() = _config.toMap()

    @JsonAnySetter
    fun put(key: String, value: Any) {
        _config[key] = value
    }
}
