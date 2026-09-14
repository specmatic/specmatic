package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.test.HttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

internal class TransientHttpStubE2ETest {
    @Nested
    inner class ConcurrentRequests {
        @Test
        fun `a one-shot transient response is served exactly once across concurrent requests`() {
            val requestCount = 32
            HttpStub(transientApi(), port = freePort()).use { stub ->
                stub.setExpectation(persistentExpectation("persistent"))
                stub.setExpectation(transientExpectation("transient"))

                val ready = CountDownLatch(requestCount)
                val start = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(requestCount)

                try {
                    val responses = List(requestCount) {
                        executor.submit(Callable {
                            ready.countDown()
                            check(start.await(5, SECONDS)) { "request did not reach the start barrier" }
                            HttpClient(stub.endPoint).use { it.execute(HttpRequest("GET", "/data")) }
                        })
                    }

                    assertThat(ready.await(5, SECONDS)).isTrue()
                    start.countDown()

                    val completedResponses = responses.map { it.get(10, SECONDS) }
                    val responseBodies = completedResponses.map { it.body.toStringLiteral() }

                    assertThat(completedResponses).hasSize(requestCount)
                    assertThat(completedResponses.map { it.status }.toSet()).isEqualTo(setOf(200))
                    assertThat(responseBodies.count { it == "transient" }).isEqualTo(1)
                    assertThat(responseBodies.count { it == "persistent" }).isEqualTo(requestCount - 1)
                    assertThat(stub.transientStubCount).isZero()
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Nested
    inner class ResponseDelivery {
        @Test
        fun `a transient response is consumed before delayed delivery to a disconnected client`() {
            val responsePrepared = CountDownLatch(1)

            HttpStub(transientApi(), port = freePort()).use { stub ->
                stub.setExpectation(persistentExpectation("persistent"))
                stub.setExpectation(transientExpectation("transient", delayInMilliseconds = 500))
                stub.registerResponseInterceptor(object : ResponseInterceptor {
                    override val name: String = "response-prepared"

                    override fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse {
                        responsePrepared.countDown()
                        return httpResponse
                    }
                })

                val endpoint = URI(stub.endPoint)
                Socket(endpoint.host, endpoint.port).use { socket ->
                    socket.getOutputStream().bufferedWriter().apply {
                        write(
                        "GET /data HTTP/1.1\r\n" +
                            "Host: ${endpoint.host}:${endpoint.port}\r\n" +
                            "Connection: close\r\n\r\n"
                        )
                        flush()
                    }

                    assertThat(responsePrepared.await(5, SECONDS)).isTrue()
                    assertThat(stub.transientStubCount).isZero()
                }

                val retryResponse = stub.client.execute(HttpRequest("GET", "/data"))
                assertThat(retryResponse.status).isEqualTo(200)
                assertThat(retryResponse.body.toStringLiteral()).isEqualTo("persistent")
            }
        }
    }

    private fun transientApi(): Feature = OpenApiSpecification.fromYAML(
        yamlContent = """
        openapi: 3.0.0
        info:
          title: Transient API
          version: 1.0.0
        paths:
          /data:
            get:
              responses:
                '200':
                  description: Data
                  content:
                    text/plain:
                      schema:
                        type: string
        """.trimIndent(),
        openApiFilePath = "transient-api.yaml"
    ).toFeature()

    @Suppress("SameParameterValue")
    private fun persistentExpectation(body: String): String =
        """
        {
            "http-request": {"method": "GET", "path": "/data"},
            "http-response": {"status": 200, "body": "$body"}
        }
        """.trimIndent()

    private fun transientExpectation(body: String, delayInMilliseconds: Long? = null): String {
        val delay = delayInMilliseconds?.let { "\n            \"delay-in-milliseconds\": $it," } ?: ""
        return """
        {
            "http-stub-id": "one-shot",
            $delay
            "http-request": {"method": "GET", "path": "/data"},
            "http-response": {"status": 200, "body": "$body"}
        }
        """.trimIndent()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
