package io.specmatic.core.config.v3

import io.specmatic.core.ProxyConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.resolveFullyOrEmpty
import io.specmatic.core.config.v3.resolveOrNull
import io.specmatic.core.config.v3.wrapFullyOrNull
import io.specmatic.core.config.v3.wrapOrNull

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
    fun toCommonConfig(resolver: RefOrValueResolver): ProxyConfig {
        return ProxyConfig(
            targetUrl = target.wrap(),
            baseUrl = baseUrl.wrapOrNull(),
            timeoutInMilliseconds = timeoutInMilliseconds.wrapOrNull(),
            adapters = adapters?.resolveElseThrow(resolver),
            consumes = mock.wrapFullyOrNull(),
            https = cert?.resolveElseThrow(resolver),
            outputDirectory = recordingsDirectory.wrapOrNull()
        )
    }
}
