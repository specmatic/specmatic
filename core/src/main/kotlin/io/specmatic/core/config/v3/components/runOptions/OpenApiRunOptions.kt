package io.specmatic.core.config.v3.components.runOptions

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.specmatic.core.WorkflowConfiguration
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve

interface ConfigWithCert { val cert: TemplateOrValue<RefOrValue<HttpsConfiguration>>? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(JsonSubTypes.Type(OpenApiTestConfig::class, name = "test"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "mock"), JsonSubTypes.Type(OpenApiMockConfig::class, name = "stateful-mock"))
sealed interface OpenApiRunOptions : IRunOptions { val type: RunOptionType? }

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiTestConfig(
    override val type: RunOptionType? = null,
    val baseUrl: TemplateOrValue<String>? = null,
    val host: TemplateOrValue<String>? = null,
    val port: TemplateOrValue<Int>? = null,
    val filter: TemplateOrValue<String>? = null,
    val workflow: TemplateOrValue<WorkflowConfiguration>? = null,
    val swaggerUiBaseUrl: TemplateOrValue<String>? = null,
    val swaggerUrl: TemplateOrValue<String>? = null,
    val actuatorUrl: TemplateOrValue<String>? = null,
    override val cert: TemplateOrValue<RefOrValue<HttpsConfiguration>>? = null,
    override val specs: TemplateOrValue<List<TemplateOrValue<OpenApiRunOptionsSpecifications>>>? = null
) : OpenApiRunOptions, ConfigWithCert {
    override val config: Map<String, TemplateOrValue<Any>> = emptyMap()

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        if (baseUrl != null) return baseUrl.resolve()
        if (port == null) return null
        return "http://${host?.resolve() ?: "localhost"}:${port.resolve()}"
    }

    init {
        require(type == null || type == RunOptionType.TEST) {
            "Invalid type '$type' for OpenApiTestConfig, expected '${RunOptionType.TEST.value}'"
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
data class OpenApiMockConfig(
    override val type: RunOptionType? = null,
    val baseUrl: TemplateOrValue<String>? = null,
    val host: TemplateOrValue<String>? = null,
    val port: TemplateOrValue<Int>? = null,
    val filter: TemplateOrValue<String>? = null,
    val logMode: TemplateOrValue<String>? = null,
    val logsDirPath: TemplateOrValue<String>? = null,
    override val cert: TemplateOrValue<RefOrValue<HttpsConfiguration>>? = null,
    override val specs: TemplateOrValue<List<TemplateOrValue<OpenApiRunOptionsSpecifications>>>? = null
) : OpenApiRunOptions, ConfigWithCert {
    override val config: Map<String, TemplateOrValue<Any>> = emptyMap()

    @JsonIgnore
    override fun getBaseUrlIfExists(): String? {
        if (baseUrl != null) return baseUrl.resolve()
        if (port == null) return null
        val scheme = if (cert == null) "http" else "https"
        return "$scheme://${host?.resolve() ?: "0.0.0.0"}:${port.resolve()}"
    }

    init {
        require(type == null || type in setOf(RunOptionType.MOCK, RunOptionType.STATEFUL_MOCK)) {
            "Invalid type '$type' for OpenApiMockConfig, expected '${RunOptionType.MOCK.value}' or '${RunOptionType.STATEFUL_MOCK}'"
        }
    }
}
