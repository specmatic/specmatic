package io.specmatic.test

import application.findRandomFreePort
import io.ktor.network.tls.certificates.generateCertificate
import io.specmatic.core.IncomingMtlsRegistry
import io.specmatic.core.KeyData
import io.specmatic.core.KeyDataRegistry
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SpecmaticMockIncomingMtlsTest {
    @Test
    fun `mock should reject request without client certificate when incoming mTLS is enabled`(@TempDir tempDir: Path) {
        val port = findRandomFreePort()
        val serverKeyData = createKeyData(
            file = tempDir.resolve("server.jks").toFile(),
            keyStorePassword = "server-store-pass",
            keyAlias = "specmatic-mock-server",
            keyPassword = "server-key-pass"
        )

        val stub = createStub(
            port = port,
            keyDataRegistry = KeyDataRegistry.empty().plus("localhost", port, serverKeyData),
            incomingMtlsRegistry = IncomingMtlsRegistry.empty().plus("localhost", port, true)
        )

        try {
            assertThatThrownBy {
                executeGet("https://localhost:$port/hello")
            }.isInstanceOf(Exception::class.java)
        } finally {
            stub.close()
        }
    }

    @Test
    fun `mock should accept request with client certificate when incoming mTLS is enabled`(@TempDir tempDir: Path) {
        val port = findRandomFreePort()
        val serverKeyData = createKeyData(
            file = tempDir.resolve("server.jks").toFile(),
            keyStorePassword = "server-store-pass",
            keyAlias = "specmatic-mock-server",
            keyPassword = "server-key-pass"
        )
        val clientKeyData = createKeyData(
            file = tempDir.resolve("client.jks").toFile(),
            keyStorePassword = "client-store-pass",
            keyAlias = "specmatic-mock-client",
            keyPassword = "client-key-pass"
        )

        val stub = createStub(
            port = port,
            keyDataRegistry = KeyDataRegistry.empty().plus("localhost", port, serverKeyData),
            incomingMtlsRegistry = IncomingMtlsRegistry.empty().plus("localhost", port, true)
        )

        try {
            assertThat(executeGet("https://localhost:$port/hello", clientKeyData)).isEqualTo(200)
        } finally {
            stub.close()
        }
    }

    private fun createStub(port: Int, keyDataRegistry: KeyDataRegistry, incomingMtlsRegistry: IncomingMtlsRegistry): HttpStub {
        val feature = parseGherkinStringToFeature(
            """
            Feature: Incoming mTLS in mock
            
            Scenario: hello
              When GET /hello
              Then status 200
              And response-body (string)
            """.trimIndent()
        )

        return HttpStub(
            features = listOf(feature),
            host = "localhost",
            port = port,
            keyDataRegistry = keyDataRegistry,
            incomingMtlsRegistry = incomingMtlsRegistry
        )
    }

    private fun createKeyData(file: File, keyStorePassword: String, keyAlias: String, keyPassword: String): KeyData {
        generateCertificate(file = file, jksPassword = keyStorePassword, keyAlias = keyAlias, keyPassword = keyPassword)
        val keyStore = KeyStore.getInstance("JKS").apply {
            file.inputStream().use { load(it, keyStorePassword.toCharArray()) }
        }
        return KeyData(
            keyStore = keyStore,
            keyStorePassword = keyStorePassword,
            keyAlias = keyAlias,
            keyPassword = keyPassword
        )
    }

    private fun executeGet(url: String, keyData: KeyData? = null): Int {
        val keyManagers: Array<KeyManager>? = keyData?.let {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(it.keyStore, it.keyPassword.toCharArray())
            }.keyManagers
        }

        val trustManagers = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, null)
        }

        return (java.net.URL(url).openConnection() as HttpsURLConnection).run {
            sslSocketFactory = sslContext.socketFactory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            requestMethod = "GET"
            connect()
            responseCode
        }
    }
}
