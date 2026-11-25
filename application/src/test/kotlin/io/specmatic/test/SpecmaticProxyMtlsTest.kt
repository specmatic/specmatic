package io.specmatic.test

import io.ktor.network.tls.certificates.generateCertificate
import io.specmatic.core.IncomingMtlsRegistry
import io.specmatic.core.KeyDataRegistry
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.KeyData
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.proxy.Proxy
import io.specmatic.proxy.resolveProxyTls
import io.specmatic.stub.HttpStub
import io.specmatic.stub.HttpStubResponse
import io.specmatic.stub.RequestHandler
import io.specmatic.stub.SpecmaticConfigSource
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.io.IOException

class SpecmaticProxyMtlsTest {
    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `records over independent incoming and outgoing TLS connections`(
        incomingMtls: Boolean,
        outgoingMtls: Boolean,
    ) {
        val targetPort = testPort()
        val recorderPort = testPort(1)
        val target = "https://localhost:$targetPort"
        val recorder = "https://localhost:$recorderPort"
        val server = identity("recorder")
        val caller = if (incomingMtls) identity("caller") else null
        val clientCert = if (outgoingMtls) {
            identity("upstream-client")
            clientCertConfig("upstream-client")
        } else {
            ""
        }
        val configSource = config("""
            proxies:
              - proxy:
                  target: $target/ignored-path
                  cert:
                    mtlsEnabled: $incomingMtls
                    keyStore:
                      file: recorder.jks
        """.trimIndent() + clientCert)

        targetStub(targetPort, outgoingMtls).use {
            startProxy(recorderPort, target, server, configSource).use {
                if (incomingMtls) {
                    HttpClient(recorder).use { client ->
                        assertThrows<IOException> { client.execute(HttpRequest("GET", "/hello")) }
                    }
                }
                HttpClient(recorder, keyData = caller).use { client ->
                    assertThat(client.execute(HttpRequest("GET", "/hello")).status).isEqualTo(200)
                }
            }
        }

        val specification = tempDir.resolve("recordings/proxy_generated.yaml").readText()
        assertThat(specification).contains("openapi:", "/hello:")
    }

    @Test
    fun `a target override cannot use an identity configured for a different port`() {
        val targetPort = testPort()
        val recorderPort = testPort(1)
        val target = "https://localhost:$targetPort"
        val recorder = "http://localhost:$recorderPort"
        identity("upstream-client")
        val configSource = config("""
            proxies:
              - proxy:
                  target: https://localhost:${testPort(2)}
        """.trimIndent() + clientCertConfig("upstream-client"))

        targetStub(targetPort, mtls = true).use {
            startProxy(recorderPort, target, configSource = configSource).use {
                HttpClient(recorder).use { client ->
                    assertThat(client.execute(HttpRequest("GET", "/hello")).status).isEqualTo(500)
                }
            }
        }
        assertThat(tempDir.resolve("recordings/proxy_generated.yaml")).doesNotExist()
    }

    @Test
    fun `returns a cross-origin redirect without forwarding the outgoing identity`() {
        val targetPort = testPort()
        val recorderPort = testPort(1)
        val target = "https://localhost:$targetPort"
        val redirectLocation = "https://localhost:${testPort(2)}/hello"
        identity("upstream-client")
        val configSource = config("""
            proxies:
              - proxy:
                  target: $target
        """.trimIndent() + clientCertConfig("upstream-client"))

        targetStub(testPort(2), mtls = true).use {
            targetStub(targetPort, mtls = true).use { provider ->
                provider.registerHandler(object : RequestHandler {
                    override val name = "Redirect to another origin"

                    override fun handleRequest(httpRequest: HttpRequest, baseUrl: String): HttpStubResponse =
                        HttpStubResponse(HttpResponse(302, "redirect", mapOf("Location" to redirectLocation)))
                })
                startProxy(recorderPort, target, configSource = configSource).use {
                    HttpClient("http://localhost:$recorderPort").use { client ->
                        val response = client.execute(HttpRequest("GET", "/hello"))
                        assertThat(response.status).isEqualTo(302)
                        assertThat(response.headers.entries.single { it.key.equals("Location", ignoreCase = true) }.value)
                            .isEqualTo(redirectLocation)
                    }
                }
            }
        }
    }

    @Test
    fun `rejects an unavailable explicit outgoing alias before starting recording`() {
        val target = "https://localhost:${testPort()}"
        identity("upstream-client")
        val configSource = config("""
            proxies:
              - proxy:
                  target: $target
        """.trimIndent() + clientCertConfig("missing"))
        val error = assertThrows<ContractException> {
            startProxy(testPort(1), target, configSource = configSource)
        }
        assertThat(error.message).contains("clientCert", "missing", "unavailable")
    }

    @Test
    fun `requires HTTPS server key material when incoming mTLS is enabled`() {
        val target = "https://localhost:${testPort()}"
        val configSource = config("""
            proxies:
              - proxy:
                  target: $target
                  cert:
                    mtlsEnabled: true
        """.trimIndent())
        val error = assertThrows<ContractException> {
            startProxy(testPort(1), target, configSource = configSource)
        }
        assertThat(error.message).contains("Incoming proxy mTLS", "server key material")
    }

    @Test
    fun `rejects multiple entries for the same origin regardless of path or default port spelling`() {
        val configSource = config("""
            proxies:
              - proxy:
                  target: https://test.example/first
              - proxy:
                  target: https://test.example:443/second
        """.trimIndent())

        val error = assertThrows<ContractException> {
            configSource.load().config.resolveProxyTls("HTTPS://TEST.EXAMPLE/other")
        }
        assertThat(error.message).contains("Multiple proxy entries", "https://test.example:443")
    }

    private fun targetStub(port: Int, mtls: Boolean): HttpStub =
        HttpStub(
            features = listOf(feature()),
            port = port,
            keyDataRegistry = KeyDataRegistry.empty().plusWildCard(identity("target-$port")),
            incomingMtlsRegistry = IncomingMtlsRegistry.empty().plusWildCard(mtls),
        )

    private fun startProxy(
        port: Int,
        target: String,
        server: KeyData? = null,
        configSource: SpecmaticConfigSource,
    ) = Proxy(
        host = "localhost",
        port = port,
        baseURL = target,
        proxySpecmaticDataDir = tempDir.resolve("recordings").path,
        keyData = server,
        timeoutInMilliseconds = 6000,
        filter = "",
        specmaticConfigSource = configSource,
    )

    private fun clientCertConfig(alias: String): String = "\n" + """
        clientCert:
          keyStore:
            file: upstream-client.jks
            alias: $alias
            password: fixture-password
          keyStorePassword: fixture-password
    """.trimIndent().prependIndent("      ")

    private fun config(yaml: String): SpecmaticConfigSource {
        val configFile = tempDir.resolve("specmatic.yaml").apply { writeText("version: 3\n$yaml\n") }
        return SpecmaticConfigSource.fromConfigFile(configFile.path)
    }

    private fun identity(name: String): KeyData {
        val password = "fixture-password"
        val store = generateCertificate(tempDir.resolve("$name.jks"), jksPassword = password, keyAlias = name, keyPassword = password)
        return KeyData(store, password, name, password)
    }

    private fun feature() = parseGherkinStringToFeature(
        """
        Feature: Recording
          Scenario: Hello
            When GET /hello
            Then status 200
            And response-body (string)
        """.trimIndent()
    )
}
