package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.WorkflowConfiguration
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.RefOrValue

interface ConfigWithCert { val cert: RefOrValue<HttpsConfiguration>? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(OpenApiTestConfig::class, name = "test"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "mock"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "stateful-mock"))
sealed interface OpenApiRunOptions : IRunOptions { val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiTestConfig(
    override val type: RunOptionType? = null,
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val filter: String? = null,
    val workflow: WorkflowConfiguration? = null,
    val swaggerUiBaseUrl: String? = null,
    val swaggerUrl: String? = null,
    val actuatorUrl: String? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiRunOptions {
    override val config: Map<String, Any> = emptyMap()

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        if (baseUrl != null) return baseUrl
        if (host == null || port == null) return null
        return "http://$host:$port"
    }

    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for OpenApiMockConfig, expected '${RunOptionType.TEST.value}'"
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiMockConfig(
    override val type: RunOptionType? = null,
    val baseUrl: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val filter: String? = null,
    val logMode: String? = null,
    val logsDirPath: String? = null,
    override val cert: RefOrValue<HttpsConfiguration>? = null,
    override val specs: List<OpenApiRunOptionsSpecifications>? = null
) : OpenApiRunOptions, ConfigWithCert {
    override val config: Map<String, Any> = emptyMap()

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        if (baseUrl != null) return baseUrl
        if (host == null || port == null) return null
        val scheme = if (cert == null) "http" else "https"
        return "$scheme://$host:$port"
    }

    init {
        require(type == null || type in setOf(RunOptionType.MOCK, RunOptionType.STATEFUL_MOCK)) {
            "Invalid type '$type' for OpenApiMockConfig, expected '${RunOptionType.MOCK.value}' or '${RunOptionType.STATEFUL_MOCK}'"
        }
    }
}
