package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ProxyConfig
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v2.SpecExecutionConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LegacySpecmaticConfigToV3UpgraderTest {
    @Test
    fun `upgrade maps proxy to listOf and sets version to 3`() {
        val config = SpecmaticConfigV1V2Common(
            sources = listOf(
                Source(
                    provider = SourceProvider.filesystem,
                    test = listOf(SpecExecutionConfig.StringValue("spec.yaml"))
                )
            ),
            proxy = ProxyConfig(
                consumes = listOf("mock-service"),
                baseUrl = "http://localhost:9090",
                targetUrl = "http://upstream:8080",
                outputDirectory = "recordings",
            )
        )

        val upgraded = LegacySpecmaticConfigToV3Upgrader().upgrade(config)
        assertThat(upgraded.version).isEqualTo(SpecmaticConfigVersion.VERSION_3)
        assertThat(upgraded.proxies).hasSize(1)

        val proxy = upgraded.proxies!!.single().proxy
        assertThat(proxy.mock).containsExactly("mock-service")
        assertThat(proxy.baseUrl).isEqualTo("http://localhost:9090")
        assertThat(proxy.target).isEqualTo("http://upstream:8080")
        assertThat(proxy.recordingsDirectory).isEqualTo("recordings")
        assertThat(proxy.cert).isNull()
        assertThat(proxy.adapters).isNull()
        assertThat(upgraded.systemUnderTest).isNotNull
        assertThat(upgraded.specmatic).isNotNull
    }

    @Test
    fun `upgrade leaves proxies null when proxy is absent`() {
        val upgraded = LegacySpecmaticConfigToV3Upgrader().upgrade(SpecmaticConfigV1V2Common())
        assertThat(upgraded.proxies).isNull()
    }
}
