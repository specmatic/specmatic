package io.specmatic.core

import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.KeyStoreConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyStore

class CertRegistryTest {
    @Test
    fun `toKeyDataRegistry should fail when multiple certs map to same host and port`() {
        val registry = CertRegistry.empty()
            .plus("https://api.example.com:9443", httpsConfig("client-a.jks"))
            .plus("https://api.example.com:9443", httpsConfig("client-b.jks"))

        assertThatThrownBy { registry.toKeyDataRegistry { null } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Multiple certificates found for the same host/port")
    }

    @Test
    fun `keyDataRegistry should prefer exact host port over wildcard`() {
        val wildcardConfig = httpsConfig("wildcard.jks")
        val exactConfig = httpsConfig("exact.jks")
        val registry = CertRegistry.empty()
            .plusWildCard(wildcardConfig)
            .plus("https://api.example.com:9443", exactConfig)

        val keyDataRegistry = registry.toKeyDataRegistry { config ->
            when (config) {
                wildcardConfig -> KeyData(emptyKeyStore(), "password", keyAlias = "wildcard")
                exactConfig -> KeyData(emptyKeyStore(), "password", keyAlias = "exact")
                else -> null
            }
        }

        assertThat(keyDataRegistry.get("api.example.com", 9443)?.keyAlias).isEqualTo("exact")
    }

    @Test
    fun `keyDataRegistry should fall back to wildcard when exact host port is unavailable`() {
        val registry = CertRegistry.empty().plusWildCard(httpsConfig("wildcard.jks"))
        val wildcardKeyData = KeyData(emptyKeyStore(), "password", keyAlias = "wildcard")

        val keyDataRegistry = registry.toKeyDataRegistry { wildcardKeyData }

        assertThat(keyDataRegistry.get("unmapped-host.example", 443)).isEqualTo(wildcardKeyData)
    }

    @Test
    fun `incoming mTLS registry should prefer exact host port over wildcard`() {
        val registry = CertRegistry.empty()
            .plusWildCard(httpsConfig("wildcard.jks", mtlsEnabled = false))
            .plus("https://api.example.com:9443", httpsConfig("exact.jks", mtlsEnabled = true))

        val incomingMtlsRegistry = registry.toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("api.example.com", 9443)).isTrue()
    }

    @Test
    fun `incoming mTLS registry should fallback to wildcard when exact host port is unavailable`() {
        val registry = CertRegistry.empty().plusWildCard(httpsConfig("wildcard.jks", mtlsEnabled = true))

        val incomingMtlsRegistry = registry.toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("unmapped-host.example", 443)).isTrue()
    }

    private fun httpsConfig(file: String, mtlsEnabled: Boolean? = null): HttpsConfiguration {
        return HttpsConfiguration(
            keyStore = KeyStoreConfiguration.FileBasedConfig(file = file),
            keyStorePassword = "password",
            mtlsEnabled = mtlsEnabled
        )
    }

    private fun emptyKeyStore(): KeyStore {
        return KeyStore.getInstance("JKS").apply { load(null, null) }
    }
}
