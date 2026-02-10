package io.specmatic.test

import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.ktor.client.request.get
import io.ktor.http.HttpProtocolVersion
import io.specmatic.core.HttpRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random


class ApacheHttpClientFactoryTest {
    @Test
    fun `the client should set a timeout policy with socketTimeout giving breathing room for requestTimeout to kick in first`() {
        val randomTimeoutInMilliseconds = Random.nextInt(1, 6) * 1000L

        val httpClientFactory: HttpClientFactory = ApacheHttpClientFactory(randomTimeoutInMilliseconds)
        val timeoutPolicyFromHttpClientFactory = httpClientFactory.timeoutPolicy

        val expectedSocketTimeout = randomTimeoutInMilliseconds + BREATHING_ROOM_FOR_REQUEST_TIMEOUT_TO_KICK_IN_FIRST

        assertThat(timeoutPolicyFromHttpClientFactory.requestTimeoutInMillis).isEqualTo(randomTimeoutInMilliseconds)
        assertThat(timeoutPolicyFromHttpClientFactory.socketTimeoutInMillis).isEqualTo(expectedSocketTimeout)
    }

    @Test
    fun `the factory should ask the timeout policy to set the timeout`() {
        val timeoutPolicy = mockk<TimeoutPolicy>()

        justRun { timeoutPolicy.configure(any()) }

        ApacheHttpClientFactory(timeoutPolicy).create().close()

        verify(exactly = 1) { timeoutPolicy.configure(any()) }
    }

    @Test
    fun `the http client should negotiate HTTP2 when the server supports it`() {
        val localhostCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhostCertificate)
            .build()

        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            server.start()

            val responseProtocol = runBlocking {
                ApacheHttpClientFactory(1000L).create().use { client ->
                    client.get(server.url("/").toString()).version
                }
            }

            assertThat(responseProtocol).isEqualTo(HttpProtocolVersion.HTTP_2_0)
        }
    }

    @Test
    fun `HttpClient should work with an HTTP2-capable target when instantiated directly`() {
        val localhostCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhostCertificate)
            .build()

        MockWebServer().use { server ->
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            server.start()

            val baseUrl = server.url("/").toString().removeSuffix("/")
            val response = HttpClient(baseUrl).use { client ->
                client.execute(HttpRequest(method = "GET", path = "/"))
            }

            assertThat(response.status).isEqualTo(200)
            assertThat(server.takeRequest().path).isEqualTo("/")
        }
    }
}
