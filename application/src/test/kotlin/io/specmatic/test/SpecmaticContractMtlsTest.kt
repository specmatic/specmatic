package io.specmatic.test

import io.ktor.network.tls.certificates.generateCertificate
import io.specmatic.core.HttpRequest
import io.specmatic.core.KeyData
import io.specmatic.core.log.LogMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer

class SpecmaticContractMtlsTest {
    @Test
    fun `should fail reachability check against mTLS endpoint when client certificate is not configured`(@TempDir tempDir: Path) {
        mtlsServer(tempDir.toFile()).use { server ->
            val baseUrl = "https://localhost:${server.port}"

            assertThat(isBaseURLReachable(baseUrl)).isFalse()
        }
    }

    @Test
    fun `should connect to mTLS endpoint with configured client certificate`(@TempDir tempDir: Path) {
        mtlsServer(tempDir.toFile()).use { server ->
            val baseUrl = "https://localhost:${server.port}"
            val keyData = KeyData(
                keyStore = loadJks(server.clientKeyStoreFile, CLIENT_KEYSTORE_PASSWORD),
                keyStorePassword = CLIENT_KEYSTORE_PASSWORD,
                keyAlias = CLIENT_ALIAS,
                keyPassword = CLIENT_KEY_PASSWORD
            )

            assertThat(isBaseURLReachable(baseUrl, keyData = keyData)).isTrue()

            val logs = mutableListOf<String>()
            val response = HttpClient(baseUrl, keyData = keyData, log = captureLogs(logs)).use { client ->
                client.execute(HttpRequest(path = "/", method = "GET"))
            }

            assertThat(response.status).isEqualTo(200)
            assertThat(logs.joinToString(System.lineSeparator())).contains("Request to $baseUrl (mTLS)")
        }
    }

    @Test
    fun `should not mark request as mTLS when server is HTTPS without client auth`(@TempDir tempDir: Path) {
        httpsServerWithoutClientAuth(tempDir.toFile()).use { server ->
            val baseUrl = "https://localhost:${server.port}"
            val keyData = KeyData(
                keyStore = loadJks(server.clientKeyStoreFile, CLIENT_KEYSTORE_PASSWORD),
                keyStorePassword = CLIENT_KEYSTORE_PASSWORD,
                keyAlias = CLIENT_ALIAS,
                keyPassword = CLIENT_KEY_PASSWORD
            )

            assertThat(isBaseURLReachable(baseUrl, keyData = keyData)).isTrue()

            val logs = mutableListOf<String>()
            val response = HttpClient(baseUrl, keyData = keyData, log = captureLogs(logs)).use { client ->
                client.execute(HttpRequest(path = "/", method = "GET"))
            }

            val renderedLog = logs.joinToString(System.lineSeparator())

            assertThat(response.status).isEqualTo(200)
            assertThat(renderedLog).contains("Request to $baseUrl at")
            assertThat(renderedLog).doesNotContain("(mTLS)")
        }
    }

    private fun mtlsServer(tempDir: File): MtlsTestServer {
        val serverKeyStoreFile = tempDir.resolve("server.jks")
        val clientKeyStoreFile = tempDir.resolve("client.jks")

        val serverKeyStore = generateCertificate(
            file = serverKeyStoreFile,
            jksPassword = SERVER_KEYSTORE_PASSWORD,
            keyAlias = SERVER_ALIAS,
            keyPassword = SERVER_KEY_PASSWORD
        )

        val clientKeyStore = generateCertificate(
            file = clientKeyStoreFile,
            jksPassword = CLIENT_KEYSTORE_PASSWORD,
            keyAlias = CLIENT_ALIAS,
            keyPassword = CLIENT_KEY_PASSWORD
        )

        val trustStore = KeyStore.getInstance("JKS").apply {
            load(null, null)
            setCertificateEntry("trusted-client", clientKeyStore.getCertificate(CLIENT_ALIAS))
        }

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKeyStore, SERVER_KEY_PASSWORD.toCharArray())
        }.keyManagers

        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }.trustManagers

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, null)
        }

        val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
        server.httpsConfigurator = object : HttpsConfigurator(sslContext) {
            override fun configure(parameters: HttpsParameters) {
                val sslParameters = sslContext.defaultSSLParameters
                sslParameters.needClientAuth = true
                parameters.setSSLParameters(sslParameters)
                parameters.needClientAuth = true
            }
        }
        server.createContext("/") { exchange ->
            val response = "ok".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { outputStream -> outputStream.write(response) }
            exchange.close()
        }

        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.start()

        return MtlsTestServer(server, executor, clientKeyStoreFile)
    }

    private fun httpsServerWithoutClientAuth(tempDir: File): MtlsTestServer {
        val serverKeyStoreFile = tempDir.resolve("server-https.jks")
        val clientKeyStoreFile = tempDir.resolve("client-https.jks")

        val serverKeyStore = generateCertificate(
            file = serverKeyStoreFile,
            jksPassword = SERVER_KEYSTORE_PASSWORD,
            keyAlias = SERVER_ALIAS,
            keyPassword = SERVER_KEY_PASSWORD
        )
        generateCertificate(
            file = clientKeyStoreFile,
            jksPassword = CLIENT_KEYSTORE_PASSWORD,
            keyAlias = CLIENT_ALIAS,
            keyPassword = CLIENT_KEY_PASSWORD
        )

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKeyStore, SERVER_KEY_PASSWORD.toCharArray())
        }.keyManagers

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, null, null)
        }

        val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
        server.httpsConfigurator = object : HttpsConfigurator(sslContext) {
            override fun configure(parameters: HttpsParameters) {
                val sslParameters = sslContext.defaultSSLParameters
                sslParameters.needClientAuth = false
                parameters.setSSLParameters(sslParameters)
                parameters.needClientAuth = false
            }
        }
        server.createContext("/") { exchange ->
            val response = "ok".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { outputStream -> outputStream.write(response) }
            exchange.close()
        }

        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.start()

        return MtlsTestServer(server, executor, clientKeyStoreFile)
    }

    private fun captureLogs(buffer: MutableList<String>): (LogMessage) -> Unit {
        return { message -> buffer.add(message.toLogString()) }
    }

    private fun loadJks(file: File, password: String): KeyStore {
        return KeyStore.getInstance("JKS").apply {
            file.inputStream().use { inputStream ->
                load(inputStream, password.toCharArray())
            }
        }
    }

    private data class MtlsTestServer(
        val httpsServer: HttpsServer,
        val executor: ExecutorService,
        val clientKeyStoreFile: File
    ) : AutoCloseable {
        val port: Int = httpsServer.address.port

        override fun close() {
            httpsServer.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private const val SERVER_ALIAS = "specmatic-server"
        private const val SERVER_KEYSTORE_PASSWORD = "server-store-pass"
        private const val SERVER_KEY_PASSWORD = "server-key-pass"

        private const val CLIENT_ALIAS = "specmatic-client"
        private const val CLIENT_KEYSTORE_PASSWORD = "client-store-pass"
        private const val CLIENT_KEY_PASSWORD = "client-key-pass"
    }
}
