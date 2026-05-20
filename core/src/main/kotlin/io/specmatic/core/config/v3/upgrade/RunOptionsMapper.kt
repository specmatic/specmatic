package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.TemplatableValue
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import io.specmatic.core.config.v3.components.runOptions.OpenApiRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.RunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.WsdlRunOptionsSpecifications
import io.specmatic.core.value
import io.specmatic.reporter.model.SpecType

data class RunOptionsMapper(
    private val defaultHostForPortOnly: String = "0.0.0.0",
    private val specIdsByPath: Map<String, String> = emptyMap(),
    private val specTypesByPath: Map<String, SpecType> = emptyMap(),
    val graphQl: Map<String, RunOptionsSpecifications.Value> = linkedMapOf(),
    val wsdl: Map<String, WsdlRunOptionsSpecifications.Value> = linkedMapOf(),
    val protobuf: Map<String, RunOptionsSpecifications.Value> = linkedMapOf(),
    val asyncApi: Map<String, RunOptionsSpecifications.Value> = linkedMapOf(),
    val openApi: Map<String, OpenApiRunOptionsSpecifications.Value> = linkedMapOf(),
) {
    fun mergeGlobalOpenApi(
        overlayFilePath: String? = null,
        securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null,
    ): RunOptionsMapper {
        return specTypesByPath.entries.fold(this) { acc, (specPath, specType) ->
            if (specType != SpecType.OPENAPI) return@fold acc
            acc.mergeGlobalOpenApi(specPath, specIdsByPath, overlayFilePath = overlayFilePath, securitySchemes = securitySchemes)
        }
    }

    fun mergeConfig(config: SpecExecutionConfig): RunOptionsMapper {
        return config.specs().fold(this) { acc, specPath ->
            val specType = specTypesByPath[specPath] ?: return@fold acc
            when (config) {
                is SpecExecutionConfig.StringValue -> acc
                is SpecExecutionConfig.ObjectValue -> acc.mergeObjectValue(specPath, specIdsByPath, specType, config)
                is SpecExecutionConfig.ConfigValue -> acc.mergeConfigValue(specPath, specIdsByPath, specType, config)
            }
        }
    }

    private fun mergeObjectValue(specPath: String, specIdsByPath: Map<String, String>, specType: SpecType, config: SpecExecutionConfig.ObjectValue): RunOptionsMapper {
        val input = config.toUrlMergeInput()
        return when (specType) {
            SpecType.WSDL -> mergeWsdl(specPath, specIdsByPath, input)
            SpecType.OPENAPI -> mergeOpenApi(specPath, specIdsByPath, input)
            SpecType.GRAPHQL -> mergeGeneric(specPath, specIdsByPath, specType, input = input)
            SpecType.ASYNCAPI -> mergeGeneric(specPath, specIdsByPath, specType, input = input)
            SpecType.PROTOBUF -> mergeGeneric(specPath, specIdsByPath, specType, input = input)
        }
    }

    private fun SpecExecutionConfig.ObjectValue.toUrlMergeInput(): UrlMergeInput {
        return when (this) {
            is SpecExecutionConfig.ObjectValue.FullUrl -> UrlMergeInput.BaseUrl(TemplatableValue(baseUrl))
            is SpecExecutionConfig.ObjectValue.PartialUrl -> UrlMergeInput.HostPort(
                host = host?.let { TemplatableValue(it) },
                port = port?.let { TemplatableValue(it) },
            )
        }
    }

    private fun SpecExecutionConfig.ConfigValue.toUrlMergeInput(): UrlMergeInput {
        val baseUrl = config["baseUrl"].asTemplatableString()
        if (baseUrl != null) return UrlMergeInput.BaseUrl(baseUrl)
        val port = config["port"].asTemplatableInt()
        val host = config["host"].asTemplatableString()
        return if (port != null) {
            UrlMergeInput.HostPort(host = host, port = port)
        } else {
            UrlMergeInput.None
        }
    }

    private fun mergeConfigValue(specPath: String, specIdsByPath: Map<String, String>, specType: SpecType, config: SpecExecutionConfig.ConfigValue): RunOptionsMapper {
        val input = config.toUrlMergeInput()
        return when (specType) {
            SpecType.WSDL -> mergeWsdl(specPath, specIdsByPath, input)
            SpecType.OPENAPI -> mergeOpenApi(specPath, specIdsByPath, input)
            SpecType.GRAPHQL -> mergeGeneric(specPath, specIdsByPath, specType, config.config, input)
            SpecType.ASYNCAPI -> mergeGeneric(specPath, specIdsByPath, specType, config.config, input)
            SpecType.PROTOBUF -> mergeGeneric(specPath, specIdsByPath, specType, config.config, input)
        }
    }

    private fun mergeOpenApi(specPath: String, specIdsByPath: Map<String, String>, input: UrlMergeInput): RunOptionsMapper {
        val existing = openApi[specPath]
        return copy(
            openApi = openApi.update(
                key = specPath,
                value = (existing ?: OpenApiRunOptionsSpecifications.Value()).copy(
                    id = specIdsByPath[specPath],
                    baseUrl = input.baseUrl(existing?.baseUrl),
                    host = input.host(existing?.host, existing?.baseUrl),
                    port = input.port(existing?.port, existing?.baseUrl),
                )
            )
        )
    }

    private fun mergeWsdl(specPath: String, specIdsByPath: Map<String, String>, input: UrlMergeInput): RunOptionsMapper {
        val existing = wsdl[specPath]
        return copy(
            wsdl = wsdl.update(
                key = specPath,
                value = (existing ?: WsdlRunOptionsSpecifications.Value()).copy(
                    id = specIdsByPath[specPath],
                    baseUrl = input.baseUrl(existing?.baseUrl),
                    host = input.host(existing?.host, existing?.baseUrl),
                    port = input.port(existing?.port, existing?.baseUrl),
                )
            )
        )
    }

    private fun mergeGeneric(specPath: String, specIdsByPath: Map<String, String>, specType: SpecType, config: Map<String, Any> = emptyMap(), input: UrlMergeInput = UrlMergeInput.None): RunOptionsMapper {
        val target = when (specType) {
            SpecType.GRAPHQL -> graphQl
            SpecType.ASYNCAPI -> asyncApi
            SpecType.PROTOBUF -> protobuf
            else -> emptyMap()
        }

        val existing = target[specPath]
        val merged = LinkedHashMap(existing?.config.orEmpty()).apply {
            putAll(config.drop("host", "port", "baseUrl"))
        }

        val updated = target.update(
            key = specPath,
            value = (existing ?: RunOptionsSpecifications.Value()).copy(
                id = specIdsByPath[specPath],
                host = input.toHost(existing?.host, defaultHostForPortOnly),
                port = input.toPort(existing?.port)
            ).withConfig(merged)
        )

        return when (specType) {
            SpecType.GRAPHQL -> copy(graphQl = updated)
            SpecType.ASYNCAPI -> copy(asyncApi = updated)
            SpecType.PROTOBUF -> copy(protobuf = updated)
            else -> this
        }
    }

    private fun mergeGlobalOpenApi(
        specPath: String,
        specIdsByPath: Map<String, String>,
        overlayFilePath: String? = null,
        securitySchemes: Map<String, SecuritySchemeConfigurationV3>? = null
    ): RunOptionsMapper {
        val existing = openApi[specPath]
        return copy(
            openApi = openApi.update(
                key = specPath,
                value = (existing ?: OpenApiRunOptionsSpecifications.Value()).copy(
                    id = specIdsByPath[specPath],
                    overlayFilePath = existing?.overlayFilePath ?: overlayFilePath,
                    securitySchemes = existing?.securitySchemes ?: securitySchemes
                )
            )
        )
    }

    @Suppress("SameParameterValue")
    private fun <T> Map<String, T>.drop(vararg keys: String): Map<String, T> {
        return this.filterKeys { key -> key !in keys }
    }

    private fun <T> Map<String, T>.update(key: String, value: T): Map<String, T> {
        return LinkedHashMap(this).apply { put(key, value) }
    }
}

private sealed interface UrlMergeInput {
    fun toPort(existingPort: TemplatableValue<Int>?): TemplatableValue<Int>?
    fun baseUrl(existing: TemplatableValue<String>?): TemplatableValue<String>?
    fun port(existing: TemplatableValue<Int>?, existingBaseUrl: TemplatableValue<String>?): TemplatableValue<Int>?
    fun host(existing: TemplatableValue<String>?, existingBaseUrl: TemplatableValue<String>?): TemplatableValue<String>?
    fun toHost(existingHost: TemplatableValue<String>?, defaultHostForPortOnly: String): TemplatableValue<String>?

    data class BaseUrl(val value: TemplatableValue<String>) : UrlMergeInput {
        override fun baseUrl(existing: TemplatableValue<String>?) = value
        override fun port(existing: TemplatableValue<Int>?, existingBaseUrl: TemplatableValue<String>?) = null
        override fun host(existing: TemplatableValue<String>?, existingBaseUrl: TemplatableValue<String>?) = null
        override fun toPort(existingPort: TemplatableValue<Int>?) = parseBaseUrlParts(value).port ?: existingPort
        override fun toHost(existingHost: TemplatableValue<String>?, defaultHostForPortOnly: String) = parseBaseUrlParts(value).host ?: existingHost
    }

    data class HostPort(val host: TemplatableValue<String>?, val port: TemplatableValue<Int>?) : UrlMergeInput {
        override fun baseUrl(existing: TemplatableValue<String>?) = existing
        override fun toPort(existingPort: TemplatableValue<Int>?) = port ?: existingPort
        override fun port(existing: TemplatableValue<Int>?, existingBaseUrl: TemplatableValue<String>?) = if (existingBaseUrl != null) null else port ?: existing
        override fun host(existing: TemplatableValue<String>?, existingBaseUrl: TemplatableValue<String>?) = if (existingBaseUrl != null) null else host ?: existing
        override fun toHost(existingHost: TemplatableValue<String>?, defaultHostForPortOnly: String): TemplatableValue<String>? {
            return host ?: if (port != null) TemplatableValue(defaultHostForPortOnly) else existingHost
        }
    }

    data object None : UrlMergeInput {
        override fun baseUrl(existing: TemplatableValue<String>?) = existing
        override fun port(existing: TemplatableValue<Int>?, existingBaseUrl: TemplatableValue<String>?) = existing
        override fun host(existing: TemplatableValue<String>?, existingBaseUrl: TemplatableValue<String>?) = existing
        override fun toHost(existingHost: TemplatableValue<String>?, defaultHostForPortOnly: String): TemplatableValue<String>? = existingHost
        override fun toPort(existingPort: TemplatableValue<Int>?) = existingPort
    }
}

private data class BaseUrlParts(val host: TemplatableValue<String>?, val port: TemplatableValue<Int>?)
private fun parseBaseUrlParts(baseUrl: TemplatableValue<String>): BaseUrlParts {
    return runCatching {
        val uri = java.net.URI(baseUrl.value)
        val parsedPort = if (uri.port == -1) null else uri.port
        BaseUrlParts(
            host = uri.host?.let { TemplatableValue(value = it, template = baseUrl.template) },
            port = parsedPort?.let { TemplatableValue(value = it, template = baseUrl.template) },
        )
    }.getOrDefault(BaseUrlParts(host = null, port = null))
}

private fun Any?.asTemplatableString(): TemplatableValue<String>? {
    return when (this) {
        is TemplatableValue<*> -> TemplatableValue(value = value.toString(), template = template)
        is String -> TemplatableValue(this)
        else -> null
    }
}

private fun Any?.asTemplatableInt(): TemplatableValue<Int>? {
    return when (this) {
        is TemplatableValue<*> -> value.toString().toIntOrNull()?.let { TemplatableValue(value = it, template = template) }
        is Number -> TemplatableValue(toInt())
        is String -> toIntOrNull()?.let { TemplatableValue(it) }
        else -> null
    }
}
