package io.specmatic.core.config.v3

import io.specmatic.core.ProxyConfig
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolveElseThrow
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.wrap

data class Proxy(val proxy: TemplateOrValue<ProxyConfigV3>)
data class ProxyConfigV3(
    val target: TemplateOrValue<String>,
    val baseUrl: TemplateOrValue<String>? = null,
    val timeoutInMilliseconds: TemplateOrValue<Long>? = null,
    val adapters: TemplateOrValue<RefOrValue<Adapter>>? = null,
    val mock: TemplateOrValue<List<TemplateOrValue<String>>>? = null,
    val cert: TemplateOrValue<RefOrValue<HttpsConfiguration>>? = null,
    val recordingsDirectory: TemplateOrValue<String>? = null,
) {
    fun toCommonConfig(resolver: RefOrValueResolver): ProxyConfig {
        return ProxyConfig(
            consumes = mock,
            baseUrl = baseUrl,
            targetUrl = target,
            outputDirectory = recordingsDirectory,
            timeoutInMilliseconds = timeoutInMilliseconds,
            https = cert?.resolveElseThrow(resolver)?.let(::wrap),
            adapters = adapters?.resolveElseThrow(resolver)?.let(::wrap),
        )
    }
}
