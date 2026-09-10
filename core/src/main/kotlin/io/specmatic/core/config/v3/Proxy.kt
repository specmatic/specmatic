package io.specmatic.core.config.v3

import io.specmatic.core.ProxyConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.validation.ConfigValidationOutput
import java.io.File

data class Proxy(val proxy: ProxyConfigV3) {
    fun validate(context: ValidationContext): List<ConfigValidationOutput> {
        return proxy.validate(context.child("proxy"))
    }
}

data class ProxyConfigV3(
    val target: String,
    val baseUrl: String? = null,
    val timeoutInMilliseconds: Long? = null,
    val adapters: RefOrValue<Adapter>? = null,
    val mock: List<String>? = null,
    val cert: RefOrValue<HttpsConfiguration>? = null,
    val recordingsDirectory: String? = null,
) {
    internal fun validate(context: ValidationContext): List<ConfigValidationOutput> {
        val adaptersOutput = adapters?.let { reference ->
            context.child("adapters").check(
                reference = reference,
                resolve = { value, resolver -> value.resolveElseThrow(resolver) },
            )
        }.orEmpty()

        val certOutput = cert?.let { reference ->
            context.child("cert").check(
                reference = reference,
                resolve = { value, resolver -> value.resolveElseThrow(resolver) },
            )
        }.orEmpty()

        return adaptersOutput + certOutput
    }

    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): ProxyConfigV3  {
        return copy(
            cert = cert?.mapValue { it.mapPaths(mapper.child("cert"), configDirectory) },
            adapters = adapters?.mapValue { it.mapPaths(mapper.child("adapters"), configDirectory) },
            mock = mock?.mapIndexed { i, path ->
                mapper.child("mock").child(i).map(path, configDirectory)
            },
            recordingsDirectory = recordingsDirectory?.let {
                mapper.child("recordingsDirectory").map(it, configDirectory)
            },
        )
    }

    fun toCommonConfig(resolver: RefOrValueResolver): ProxyConfig {
        return ProxyConfig(
            targetUrl = target,
            baseUrl = baseUrl,
            timeoutInMilliseconds = timeoutInMilliseconds,
            adapters = adapters?.resolveElseThrow(resolver),
            consumes = mock,
            https = cert?.resolveElseThrow(resolver),
            outputDirectory = recordingsDirectory
        )
    }
}
