package io.specmatic.proxy

import io.specmatic.core.KeyData
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.config.HttpsConfiguration
import io.specmatic.core.config.KeyStoreConfiguration
import io.specmatic.core.config.v3.ProxyClientCertificate
import io.specmatic.core.config.v3.SpecmaticConfigV3Impl
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.toKeyData
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Locale

sealed interface ProxyTlsSource {
    data class FromConfig(val override: KeyData? = null) : ProxyTlsSource
    data class Explicit(val config: ProxyTlsConfig) : ProxyTlsSource
}

data class ProxyTlsConfig(
    val cert: HttpsConfiguration? = null,
    val clientCert: ProxyClientCertificate? = null,
)

fun SpecmaticConfig.resolveProxyTls(effectiveTarget: String): ProxyTlsConfig {
    if (this !is SpecmaticConfigV3Impl) {
        val proxy = getProxyConfig() ?: return ProxyTlsConfig()
        val targetOrigin = ProxyTargetOrigin.from(effectiveTarget)
        val clientCert = proxy.clientCert?.takeIf {
            targetOrigin != null && targetOrigin == ProxyTargetOrigin.from(proxy.targetUrl)
        }
        val directory = (this as? SpecmaticConfigV1V2Common)?.proxyConfigDirectory ?: File(".")
        return ProxyTlsConfig(cert = proxy.getHttpsConfig(), clientCert = clientCert?.resolveFile(directory))
    }

    val origin = ProxyTargetOrigin.from(effectiveTarget) ?: return ProxyTlsConfig()
    return resolveProxyTls(origin)
}

data class ProxyTargetOrigin private constructor(val scheme: String, val host: String, val port: Int) {
    companion object {
        fun from(url: String): ProxyTargetOrigin? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val defaultPort = when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> return null
            }
            return ProxyTargetOrigin(scheme, host, if (uri.port == -1) defaultPort else uri.port)
        }
    }
}

internal fun ProxyClientCertificate.loadKeyData(): KeyData {
    val configuration = HttpsConfiguration(
        keyStore = KeyStoreConfiguration.FileBasedConfig(keyStore.file, keyStore.password, keyStore.alias),
        keyStorePassword = keyStorePassword,
    )
    val keyData = configuration.toKeyData(aliasSuffix = "proxy-client")
        ?: throw ContractException("Proxy clientCert requires a keystore file")
    val alias = keyStore.alias ?: return keyData.copy(keyAlias = "")
    val password = keyData.keyPassword.toCharArray()
    val privateKey = keyData.keyStore.getKey(alias, password) as? PrivateKey
    val chain = keyData.keyStore.getCertificateChain(alias)
    if (privateKey == null || chain.isNullOrEmpty()) {
        throw ContractException("Proxy clientCert alias '$alias' is unavailable as a private key with a certificate chain")
    }

    // Limit the TLS key manager to the explicitly configured identity.
    val selectedKeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry(alias, privateKey, password, chain)
    }
    return keyData.copy(keyStore = selectedKeyStore)
}
