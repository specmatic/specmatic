package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.v3.ServerOrigin
import io.specmatic.core.config.v3.ValidationContext
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.validation.ConfigValidationOutput
import java.io.File

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(ArazzoTestConfig::class, name = "test"), JsonSubTypes.Type(ArazzoMockConfig::class, name = "mock"))
sealed interface ArazzoRunOptions : IRunOptions {
    val type: RunOptionType?

    @JsonIgnore
    override fun getServerOrigin(specId: String?): ServerOrigin? = null

    override fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ArazzoRunOptions
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class ArazzoTestConfig(
    override val type: RunOptionType? = null,
    override val specs: List<RunOptionsSpecifications>? = null,
    @JsonIgnore private val _config: MutableMap<String, Any> = linkedMapOf(),
) : ArazzoRunOptions {
    fun withConfig(newConfig: Map<String, Any>): ArazzoTestConfig = copy(_config = LinkedHashMap(newConfig))

    override fun validateForSpecFile(
        specFile: File,
        definition: SpecificationDefinition,
        validationContext: ValidationContext,
    ): List<ConfigValidationOutput> = validateProtocolConfigForSpecFile(
        specFile = specFile,
        definition = definition,
        runOptionType = type ?: RunOptionType.TEST,
        validationContext = validationContext.child("arazzo"),
    )

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

    override fun validateForSpecFile(
        specFile: File,
        definition: SpecificationDefinition,
        validationContext: ValidationContext,
    ): List<ConfigValidationOutput> = validateProtocolConfigForSpecFile(
        specFile = specFile,
        definition = definition,
        runOptionType = type ?: RunOptionType.MOCK,
        validationContext = validationContext.child("arazzo"),
    )

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
