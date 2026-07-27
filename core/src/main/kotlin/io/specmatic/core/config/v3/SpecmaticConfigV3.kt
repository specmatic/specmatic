package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.McpConfiguration
import io.specmatic.core.config.ConfigPathMapper
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.SpecmaticVersionedConfig
import io.specmatic.core.config.SpecmaticVersionedConfigLoader
import io.specmatic.core.config.v3.upgrade.LegacySpecmaticConfigToV3Upgrader
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.pattern.ContractException
import java.io.File

data class SpecmaticConfigV3(
    val version: SpecmaticConfigVersion,
    val systemUnderTest: TestServiceConfig? = null,
    val dependencies: MockServiceConfig? = null,
    val proxies: List<Proxy>? = null,
    val mcp: McpConfiguration? = null,
    val specmatic: Specmatic? = null,
    val components: Components? = null,
) : SpecmaticVersionedConfig {
    fun mapPaths(mapper: ConfigPathMapper, configDirectory: File): SpecmaticConfigV3 {
        val resolver = SpecmaticConfigV3Resolver(components ?: Components(), configDirectory.toPath())
        val mappedComponents = components?.mapPaths(mapper.child("components"), configDirectory)
        val sourceReferences = mappedComponents?.sources.orEmpty()

        val mappedProxies = proxies?.mapIndexed { index, proxy ->
            proxy.copy(
                proxy = proxy.proxy.mapPaths(
                    mapper.child("proxies").child(index).child("proxy"), configDirectory
                )
            )
        }

        return copy(
            proxies = mappedProxies,
            components = mappedComponents,
            mcp = mcp?.mapPaths(mapper.child("mcp"), configDirectory),
            specmatic = specmatic?.mapPaths(mapper.child("specmatic"), configDirectory),
            dependencies = dependencies?.mapPaths(mapper.child("dependencies"), configDirectory, sourceReferences, resolver),
            systemUnderTest = systemUnderTest?.mapPaths(mapper.child("systemUnderTest"), configDirectory, sourceReferences, resolver),
        )
    }

    override fun transform(file: File?): SpecmaticConfig {
        return SpecmaticConfigV3Impl(file, this)
    }

    companion object : SpecmaticVersionedConfigLoader {
        override fun loadFrom(config: SpecmaticConfig): SpecmaticConfigV3 {
            return when (config) {
                is SpecmaticConfigV3Impl -> config.specmaticConfig
                is SpecmaticConfigV1V2Common -> LegacySpecmaticConfigToV3Upgrader().upgrade(config)
                else -> throw ContractException("Expected v1, v2, or v3 config format, but got an incompatible config structure.")
            }
        }
    }
}
