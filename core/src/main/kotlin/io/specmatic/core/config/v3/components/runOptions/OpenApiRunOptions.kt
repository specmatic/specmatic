package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.WorkflowConfiguration
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.RefOrValue

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(OpenApiTestConfig::class, name = "test"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "mock"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "stateful-mock"))
sealed interface OpenApiRunOptions : IRunOptions { val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiTestConfig(
    override val type: RunOptionType? = null,
    override val baseUrl: String,
    val filter: String? = null,
    val workflow: WorkflowConfiguration? = null,
    val swaggerUiBaseUrl: String? = null,
    val swaggerUrl: String? = null,
    val actuatorUrl: String? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiRunOptions {
    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for OpenApiMockConfig, expected '${RunOptionType.TEST.value}'"
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiMockConfig(
    override val type: RunOptionType? = null,
    override val baseUrl: String,
    val filter: String? = null,
    val logMode: String? = null,
    val logsDirPath: String? = null,
    val cert: RefOrValue<HttpsConfiguration>? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiRunOptions {
    init {
        require(type == null || type in setOf(RunOptionType.MOCK, RunOptionType.STATEFUL_MOCK)) {
            "Invalid type '$type' for OpenApiMockConfig, expected '${RunOptionType.MOCK.value}' or '${RunOptionType.STATEFUL_MOCK}'"
        }
    }
}
