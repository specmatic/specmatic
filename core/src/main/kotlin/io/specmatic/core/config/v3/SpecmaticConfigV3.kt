package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.McpConfiguration
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
    val systemUnderTest: TemplateOrValue<TestServiceConfig>? = null,
    val dependencies: TemplateOrValue<MockServiceConfig>? = null,
    val proxies: TemplateOrValue<List<TemplateOrValue<Proxy>>>? = null,
    val mcp: TemplateOrValue<McpConfiguration>? = null,
    val specmatic: TemplateOrValue<Specmatic>? = null,
    val components: TemplateOrValue<Components>? = null,
) : SpecmaticVersionedConfig {
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
