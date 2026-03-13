package io.specmatic.core

import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.value.StringValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.HttpStub
import io.specmatic.stub.ktorHttpRequestToHttpRequest
import io.specmatic.stub.respondToKtorHttpResponse
import io.specmatic.test.HttpClient
import io.specmatic.test.HttpInteractionsLog
import io.specmatic.test.LegacyHttpClient
import io.specmatic.test.SET_COOKIE_SEPARATOR
import io.specmatic.test.internalHeadersToKtorHeaders
import io.specmatic.test.ktorHeadersToInternalHeaders
import io.specmatic.test.targetServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class HttpClientTest {
    @Test
    fun `should append mTLS marker to target server when mtls was negotiated`() {
        val target = targetServer("https://localhost:9443", mtlsNegotiated = true)

        assertThat(target).isEqualTo("https://localhost:9443 (mTLS)")
    }

    @Test
    fun `should not append mTLS marker to target server when mtls was not negotiated`() {
        val target = targetServer("https://localhost:9443", mtlsNegotiated = false)

        assertThat(target).isEqualTo("https://localhost:9443")
    }

    @Test
    fun `should not append mTLS marker to target server when base URL is HTTP`() {
        val target = targetServer("http://localhost:9000", mtlsNegotiated = true)

        assertThat(target).isEqualTo("http://localhost:9000")
    }


    @Test
    fun clientShouldNotRedirect() {
        val server = embeddedServer(Netty, port = 8080) {
            routing {
                get("/some/redirect") {
                    call.respondRedirect("/newUrl")
                }

                get("/newUrl") {
                    call.respond("")
                }
            }
        }

        try {
            server.start(wait = false)

            val request = HttpRequest().updateMethod("GET").updatePath("/some/redirect")
            val response = LegacyHttpClient("http://localhost:8080").execute(request)
            Assertions.assertEquals(302, response.status)
            Assertions.assertEquals("/newUrl", response.headers["Location"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun clientShouldGenerateRequestAndParseResponse() {
        val request = HttpRequest().updateMethod("POST").updatePath("/balance").updateQueryParam("account-id", "10")
            .updateBody("{name: \"Sherlock\", address: \"221 Baker Street\"}")
        val contractGherkin = "" +
                "Feature: Unit test\n\n" +
                "  Scenario: Unit test\n" +
                "    Given POST /balance?account-id=(number)\n" +
                "    When request-body {name: \"(string)\", address: \"(string)\"}\n" +
                "    Then status 200\n" +
                "    And response-body {location-id: 10}"
        val host = "localhost"
        val port = 8080
        val url = "http://localhost:$port"
        val client = LegacyHttpClient(url)
        HttpStub(contractGherkin, emptyList(), host, port).use {
            val response = client.execute(request)
            Assertions.assertNotNull(response)
            Assertions.assertEquals(200, response.status)
            val jsonResponseBody = JSONObject(response.body.toStringLiteral())
            Assertions.assertEquals(10, jsonResponseBody.getInt("location-id"))
        }
    }

    @Test
    fun clientShouldPerformServerSetup() {
        val request = HttpRequest().updateMethod("POST").updatePath("/balance").updateQueryParam("account-id", "10")
            .updateBody("{name: \"Sherlock\", address: \"221 Baker Street\"}")
        val contractGherkin = "" +
                "Feature: Unit test\n\n" +
                "  Scenario: Unit test\n" +
                "    Given fact server state\n" +
                "    When POST /balance?account-id=(number)\n" +
                "    And request-body {name: \"(string)\", address: \"(string)\"}\n" +
                "    Then status 200\n" +
                "    And response-body {location-id: 10}"
        val host = "localhost"
        val port = 8080
        val url = "http://localhost:$port"
        val client = LegacyHttpClient(url)

        HttpStub(contractGherkin, emptyList(), host, port).use {
            client.setServerState(mapOf("server" to StringValue("state")))
            val response = client.execute(request)
            Assertions.assertNotNull(response)
            Assertions.assertEquals(200, response.status)
            val jsonResponseBody = JSONObject(response.body.toStringLiteral())
            Assertions.assertEquals(10, jsonResponseBody.getInt("location-id"))
        }
    }

    @Test
    fun `should handle Set-Cookie header case insensitively`() {
        val headers = mapOf("set-cookie" to listOf("a=1", "b=2"))
        val internal = ktorHeadersToInternalHeaders(headers)
        assertThat(internal["set-cookie"]).isEqualTo("a=1${SET_COOKIE_SEPARATOR}b=2")
    }

    @Test
    fun `should join multiple Set-Cookie headers using null separator`() {
        val ktorHeaders = mapOf(
            HttpHeaders.SetCookie to listOf("a=1; Path=/", "b=2; HttpOnly"),
            HttpHeaders.ContentType to listOf("application/json")
        )

        val internal = ktorHeadersToInternalHeaders(ktorHeaders)
        assertThat(internal[HttpHeaders.SetCookie]).isEqualTo("a=1; Path=/${SET_COOKIE_SEPARATOR}b=2; HttpOnly")
        assertThat(internal[HttpHeaders.ContentType]).isEqualTo("application/json")
    }

    @Test
    fun `should split internal Set-Cookie header back into multiple values`() {
        val internalHeaders = mapOf(
            HttpHeaders.SetCookie to "a=1; Path=/${SET_COOKIE_SEPARATOR}b=2; HttpOnly",
            HttpHeaders.ContentType to "application/json"
        )

        val ktorHeaders = internalHeadersToKtorHeaders(internalHeaders)
        assertThat(ktorHeaders[HttpHeaders.SetCookie]).containsExactly("a=1; Path=/", "b=2; HttpOnly")
        assertThat(ktorHeaders[HttpHeaders.ContentType]).containsExactly("application/json")
    }

    @Test
    fun setCookieHeadersShouldBeMultipleOnWire() {
        val server = embeddedServer(Netty, port = 8080) {
            routing {
                get("/cookies") {
                    val internalResponse = HttpResponse(status = 200, headers = mapOf(HttpHeaders.SetCookie to "a=1; Path=/${SET_COOKIE_SEPARATOR}b=2; HttpOnly"))
                    respondToKtorHttpResponse(call, internalResponse)
                }
            }
        }

        try {
            server.start(wait = false)
            val rawResponse = runBlocking { io.ktor.client.HttpClient().use { client -> client.get("http://localhost:8080/cookies") } }
            assertThat(rawResponse.headers.getAll(HttpHeaders.SetCookie)).containsExactly("a=1; Path=/", "b=2; HttpOnly")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `ensure that httpLogMessage is not overwritten when executing a request`() {
        val port = ServerSocket(0).use { it.localPort }

        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/data") {
                    val internalResponse = HttpResponse(status = 200)
                    respondToKtorHttpResponse(call, internalResponse)
                }
            }
        }

        try {
            server.start(wait = false)

            val httpLogMessage = HttpLogMessage()
            val httpInteractionsLog = HttpInteractionsLog()

            val client = HttpClient(
                "http://localhost:$port",
                httpLogMessage = httpLogMessage,
                httpInteractionsLog = httpInteractionsLog
            )

            val request = HttpRequest().updateMethod("GET").updatePath("/data")

            client.execute(request)

            assertThat(httpLogMessage.response).isNotNull
        } finally {
            server.stop()
        }
    }

    @Test
    fun `ensure that httpLogMessage is not overwritten when preExecuteScenario is called`() {
        val port = ServerSocket(0).use { it.localPort }

        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/data") {
                    val internalResponse = HttpResponse(status = 200)
                    respondToKtorHttpResponse(call, internalResponse)
                }
            }
        }

        try {
            server.start(wait = false)

            val httpLogMessage = HttpLogMessage()
            val httpInteractionsLog = HttpInteractionsLog()

            val client = HttpClient(
                "http://localhost:$port",
                httpLogMessage = httpLogMessage,
                httpInteractionsLog = httpInteractionsLog
            )

            val request = HttpRequest().updateMethod("GET").updatePath("/data")
            val dummyScenario = Scenario(ScenarioInfo(protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI))
            client.preExecuteScenario(dummyScenario, request)

            client.execute(request)

            assertThat(httpInteractionsLog.testHttpLogMessages.first().response).isNotNull
        } finally {
            server.stop()
        }
    }
}
