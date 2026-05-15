package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ProxyConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v3.Proxy
import io.specmatic.core.config.v3.ProxyConfigV3
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.components.Adapter

class LegacySpecmaticConfigToV3Upgrader {
    fun upgrade(legacyConfig: SpecmaticConfigV1V2Common): SpecmaticConfigV3 {
        val view = LegacyConfigView.from(legacyConfig)
        val source = SourceMigrationBuilder(view.gitAuth)
        return SpecmaticConfigV3(
            mcp = legacyConfig.getMcpConfiguration(),
            version = SpecmaticConfigVersion.VERSION_3,
            dependencies = DependenciesMapper().mapFrom(view, source),
            systemUnderTest = SystemUnderTestMapper().mapFrom(view, source),
            specmatic = SpecmaticMetadataMapper().mapFrom(legacyConfig, view),
            proxies = legacyConfig.getProxyConfig()?.let { listOf(Proxy(it.toV3(view.proxyHooks))) },
        )
    }

    private fun ProxyConfig.toV3(hooks: Map<String, String>): ProxyConfigV3 {
        val mergedAdapters = hooks.plus(adapters?.hooks.orEmpty()).takeUnless { it.isEmpty() }
        return ProxyConfigV3(
            mock = consumes,
            baseUrl = baseUrl,
            target = targetUrl,
            recordingsDirectory = outputDirectory,
            cert = https?.let { RefOrValue.Value(it) },
            timeoutInMilliseconds = timeoutInMilliseconds,
            adapters = mergedAdapters?.let { RefOrValue.Value(Adapter(it)) }
        )
    }
}
