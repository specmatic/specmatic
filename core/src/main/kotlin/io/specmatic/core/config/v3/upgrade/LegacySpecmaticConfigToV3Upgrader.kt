package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ProxyConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.ConfigTemplateMetadata
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
        val dependenciesMapper = DependenciesMapper()
        val systemUnderTestMapper = SystemUnderTestMapper()
        val specmaticMetadataMapper = SpecmaticMetadataMapper()
        val templateMetadata = legacyConfig.configTemplateMetadata.transferToV3()
            .let { metadata -> source.transferMetadata(view.sources, metadata) }
            .let { metadata -> dependenciesMapper.transferMetadata(view, metadata) }
            .let { metadata -> systemUnderTestMapper.transferMetadata(view, metadata) }
            .let(specmaticMetadataMapper::transferMetadata)
            .let(::transferProxyMetadata)
            .let(::transferMcpMetadata)

        return SpecmaticConfigV3(
            mcp = legacyConfig.getMcpConfiguration(),
            version = SpecmaticConfigVersion.VERSION_3,
            dependencies = dependenciesMapper.mapFrom(view, source),
            systemUnderTest = systemUnderTestMapper.mapFrom(view, source),
            specmatic = specmaticMetadataMapper.mapFrom(legacyConfig, view),
            proxies = legacyConfig.getProxyConfig()?.let { listOf(Proxy(it.toV3(view.proxyHooks))) },
            configTemplateMetadata = templateMetadata,
        )
    }

    private fun ProxyConfig.toV3(hooks: Map<String, String>): ProxyConfigV3 {
        val mergedAdapters = adapters?.hooks.orEmpty().plus(hooks).takeUnless { it.isEmpty() }
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

    private fun transferProxyMetadata(metadata: ConfigTemplateMetadata): ConfigTemplateMetadata {
        return metadata.transferTemplatesUnder(
            sourcePrefix = listOf("proxy"),
            targetPrefix = listOf("proxies", "0", "proxy"),
            suffixTransform = { suffix ->
                when (suffix.firstOrNull()) {
                    "host", "port" -> null
                    "targetUrl" -> listOf("target") + suffix.drop(1)
                    "outputDirectory" -> listOf("recordingsDirectory") + suffix.drop(1)
                    "consumes" -> listOf("mock") + suffix.drop(1)
                    "https" -> listOf("cert") + suffix.drop(1)
                    else -> suffix
                }
            }
        ).transferTemplatesUnder(
            sourcePrefix = listOf("hooks"),
            targetPrefix = listOf("proxies", "0", "proxy", "adapters"),
        )
    }

    private fun transferMcpMetadata(metadata: ConfigTemplateMetadata): ConfigTemplateMetadata {
        return metadata.transferTemplatesUnder(listOf("mcp"), listOf("mcp"))
    }
}
