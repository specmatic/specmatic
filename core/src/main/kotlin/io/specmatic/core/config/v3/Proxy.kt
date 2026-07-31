package io.specmatic.core.config.v3

import io.specmatic.core.ProxyConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.v3.components.Adapter
import java.io.File

data class Proxy(val proxy: ProxyConfigV3)
data class ProxyConfigV3(
    val target: String,
    val baseUrl: String? = null,
    val timeoutInMilliseconds: Long? = null,
    val adapters: RefOrValue<Adapter>? = null,
    val mock: List<String>? = null,
    val cert: RefOrValue<HttpsConfiguration>? = null,
    val recordingsDirectory: String? = null,
) {
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
