package io.specmatic.core.config.v3.components.runOptions.openapi

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.WorkflowConfiguration
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptionType

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(OpenApiTestConfig::class, name = "test"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "mock"), JsonSubTypes.Type(OpenApiStatefulMockConfig::class, name = "stateful-mock"))
sealed interface OpenApiRunOptions : IRunOptions { val type: RunOptionType? }

sealed interface OpenApiTestRunOptions : OpenApiRunOptions

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiTestConfig(
    val baseUrl: String? = null,
    val workflow: WorkflowConfiguration? = null,
    val swaggerUrl: String? = null,
    val actuatorUrl: String? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiTestRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.TEST) {
            "Invalid type '$input' for OpenApiMockConfig, expected '${RunOptionType.TEST.value}'"
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true, defaultImpl = OpenApiMockConfig::class)
@JsonSubTypes(JsonSubTypes.Type(OpenApiMockConfig::class, name = "mock"), JsonSubTypes.Type(OpenApiStatefulMockConfig::class, name = "stateful-mock"))
sealed interface OpenApiMockRunOptions : OpenApiRunOptions {
    val baseUrl: String?
    val cert: RefOrValue<HttpsConfiguration>?
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiMockConfig(
    override val baseUrl: String? = null,
    override val cert: RefOrValue<HttpsConfiguration>? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiMockRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.MOCK) {
            "Invalid type '$input' for OpenApiMockConfig, expected '${RunOptionType.MOCK.value}'"
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiStatefulMockConfig(
    override val baseUrl: String? = null,
    val logMode: String? = null,
    val logsDirPath: String? = null,
    override val cert: RefOrValue<HttpsConfiguration>? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiMockRunOptions {
    @JsonIgnore
    override val type: RunOptionType? = null

    @JsonProperty("type")
    private fun setType(input: RunOptionType?) {
        require(input == null || input == RunOptionType.STATEFUL_MOCK) {
            "Invalid type '$input' for OpenApiStatefulMockConfig, expected '${RunOptionType.STATEFUL_MOCK.value}'"
        }
    }
}
