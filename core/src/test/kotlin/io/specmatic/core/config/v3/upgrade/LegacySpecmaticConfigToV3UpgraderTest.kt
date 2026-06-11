package io.specmatic.core.config.v3.upgrade

import io.specmatic.core.ProxyConfig
import io.specmatic.core.Source
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v3.components.Adapter
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

    @Test
    fun `upgrade routes stub hook to stub data and proxy hook to proxy adapters`() {
        val config = SpecmaticConfigV1V2Common(
            sources = listOf(
                Source(
                    provider = SourceProvider.filesystem,
                    stub = listOf(SpecExecutionConfig.StringValue("spec.yaml"))
                )
            ),
            proxy = ProxyConfig(baseUrl = "http://localhost:9090", targetUrl = "http://upstream:8080"),
            hooks = mapOf(
                "pre_specmatic_request_processor" to "cat request.txt",
                "pre_specmatic_response_processor" to "cat proxy-response.txt",
                "post_specmatic_response_processor" to "cat stub-response.txt",
                "custom_hook" to "cat custom.txt",
            )
        )

        val upgraded = LegacySpecmaticConfigToV3Upgrader().upgrade(config)
        val dependenciesHooks = upgraded.dependencies?.data?.adapters?.getUnsafe()?.hooks.orEmpty()
        val proxyHooks = upgraded.proxies?.single()?.proxy?.adapters?.getUnsafe()?.hooks.orEmpty()

        assertThat(dependenciesHooks["pre_specmatic_request_processor"]).isEqualTo("cat request.txt")
        assertThat(dependenciesHooks["post_specmatic_response_processor"]).isEqualTo("cat stub-response.txt")
        assertThat(dependenciesHooks["custom_hook"]).isEqualTo("cat custom.txt")
        assertThat(dependenciesHooks["pre_specmatic_response_processor"]).isNull()

        assertThat(proxyHooks["pre_specmatic_request_processor"]).isEqualTo("cat request.txt")
        assertThat(proxyHooks["pre_specmatic_response_processor"]).isEqualTo("cat proxy-response.txt")
        assertThat(proxyHooks["custom_hook"]).isEqualTo("cat custom.txt")
        assertThat(proxyHooks["post_specmatic_response_processor"]).isNull()
    }

    @Test
    fun `upgrade lets proxy adapters override global proxy hooks`() {
        val config = SpecmaticConfigV1V2Common(
            proxy = ProxyConfig(
                baseUrl = "http://localhost:9090",
                targetUrl = "http://upstream:8080",
                adapters = Adapter(
                    mapOf(
                        "stub_load_contract" to "cat proxy-stub-contract.txt",
                        "test_load_contract" to "cat proxy-test-contract.txt",
                    )
                )
            ),
            hooks = mapOf(
                "stub_load_contract" to "cat global-stub-contract.txt",
                "test_load_contract" to "cat global-test-contract.txt",
            )
        )

        val upgraded = LegacySpecmaticConfigToV3Upgrader().upgrade(config)
        val proxyHooks = upgraded.proxies?.single()?.proxy?.adapters?.getUnsafe()?.hooks.orEmpty()

        assertThat(proxyHooks["stub_load_contract"]).isEqualTo("cat proxy-stub-contract.txt")
        assertThat(proxyHooks["test_load_contract"]).isEqualTo("cat proxy-test-contract.txt")
    }
}
