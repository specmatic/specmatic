package io.specmatic.core.config.v3

import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.*
import io.specmatic.core.config.v3.components.MockServiceConfig
import io.specmatic.core.config.v3.components.TestServiceConfig
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
    override fun transform(file: File?): SpecmaticConfig {
        return SpecmaticConfigV3Impl(file, this)
    }

    companion object : SpecmaticVersionedConfigLoader {
        private fun currentConfigVersion(): SpecmaticConfigVersion = SpecmaticConfigVersion.VERSION_3

        override fun loadFrom(config: SpecmaticConfig): SpecmaticConfigV3 {
            return SpecmaticConfigV3(currentConfigVersion())
        }
    }
}

fun main() {
    val file = File("D:\\GitHub\\Config_V3").resolve("specmatic.yaml")
    val specmaticConfig = file.toSpecmaticConfig()
}
