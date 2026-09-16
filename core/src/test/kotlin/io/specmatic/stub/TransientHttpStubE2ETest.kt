package io.specmatic.stub

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.LogMessage
import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.utilities.ContractPathData
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.TestResult
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener
import io.specmatic.test.HttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.stream.Stream

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

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class ConnectionTermination {
        @BeforeEach
        fun installMatcherServices() {
            mockkObject(MatcherEngine.Companion)
            every { MatcherEngine.load() } returns FakeMatcherEngine

            mockkObject(HttpStubMatcherFactory.Companion)
            every { HttpStubMatcherFactory.load() } returns FakeHttpStubMatcherFactory
        }

        @AfterEach
        fun uninstallMatcherServices() {
            unmockkObject(HttpStubMatcherFactory.Companion)
            unmockkObject(MatcherEngine.Companion)
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("terminatingExamples")
        fun `a matching external example closes the client connection`(name: String, requestPath: String, body: String) {
            loadedStub().use { stub ->
                val firstResponseByte = readFirstResponseByte(stub.endPoint, rawPost(requestPath, body))
                assertThat(firstResponseByte).isEqualTo(-1)
            }
        }

        @Test
        fun `a terminated response is logged recorded and sent to listeners`() {
            val mockEvents = mutableListOf<MockEvent>()
            val loggedMessages = mutableListOf<LogMessage>()
            val listener = object : MockEventListener {
                override fun onRespond(data: MockEvent) {
                    mockEvents.add(data)
                }
            }

            val fixture = loadFixture()
            val port = freePort()
            HttpStub(
                port = port,
                listeners = listOf(listener),
                log = {  loggedMessages.add(it) },
                features = listOf(fixture.feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(fixture.feature to fixture.scenarioStubs)),
            ).use { stub ->
                val firstResponseByte = readFirstResponseByte(
                    endpoint = stub.endPoint,
                    rawRequest = rawPost("/normal", """{"id":1,"name":"normal"}"""),
                )

                assertThat(firstResponseByte).isEqualTo(-1)
                val record = stub.ctrfTestResultRecords().single()
                assertThat(record.matchesResponseIdentifiers).isFalse
                assertThat(record.exampleId).isEqualTo("normal")
                assertThat(record.response?.status).isEqualTo(0)
                assertThat(record.actualResponseStatus).isEqualTo(0)
                assertThat(record.connectionTerminated).isTrue()
                assertThat(record.result).isEqualTo(TestResult.Success)
                assertThat(record.response?.body?.toStringLiteral()).isEqualTo("Connection terminated.\nNo HTTP response was sent.")
            }

            val logMessage = loggedMessages.single() as HttpLogMessage
            assertThat(logMessage.response?.status).isEqualTo(0)
            assertThat(logMessage.response?.body?.toStringLiteral()).isEqualTo("Connection terminated.\nNo HTTP response was sent.")
            assertThat(logMessage.toLogString()).isEqualToIgnoringWhitespace("""
            --------------------
            Contract matched: ${fixture.feature.path}
            External Example matched: normal

            Request to port '$port' at ${logMessage.requestTime}
              POST /normal
              Host: localhost
              Content-Type: application/json
              Content-Length: 24
              Connection: keep-alive
              {
                  "id": 1,
                  "name": "normal"
              }

            Response at ${logMessage.responseTime}
              Connection terminated.
              No HTTP response was sent.
            """.trimIndent())

            val event = mockEvents.single()
            assertThat(event.response?.status).isEqualTo(0)
            assertThat(event.response?.body?.toStringLiteral()).isEqualTo("Connection terminated.\nNo HTTP response was sent.")
            assertThat(event.stubResult).isEqualTo(TestResult.Success)
            assertThat(event.terminatedConnection).isTrue()
        }

        @Test
        fun `a request that does not match the terminating example receives the ordinary response`() {
            loadedStub().use { stub ->
                val response = stub.client.execute(postRequest("/normal", """{"id":999,"name":"regular"}"""))
                assertThat(response.status).isEqualTo(200)
                assertThat(response.body).isEqualTo(parsedJSONObject("""{"source":"normal-fallback"}"""))
            }
        }

        @Test
        fun `a transient external example is exhausted after it terminates once`() {
            loadedStub().use { stub ->
                val firstResponse = readFirstResponseByte(stub.endPoint, rawPost(path = "/transient", body = """{"id":4,"name":"transient"}"""))
                assertThat(firstResponse).isEqualTo(-1)

                val secondResponse = stub.client.execute(postRequest(path = "/transient", body = """{"id":4,"name":"transient"}"""))
                assertThat(secondResponse.status).isEqualTo(200)
                assertThat(secondResponse.headers["X-Specmatic-Type"]).isEqualTo("random")
            }
        }

        @Test
        fun `a transient repetition matcher terminates for each allowed match and then is exhausted`() {
            loadedStub().use { stub ->
                val body = """[{"value":"first"}]"""
                repeat(2) {
                    assertThat(readFirstResponseByte(stub.endPoint, rawPost("/matcher-times", body))).isEqualTo(-1)
                }

                val response = stub.client.execute(postRequest("/matcher-times", body, parseJson = false))
                assertThat(response.status).isEqualTo(200)
                assertThat(response.headers["X-Specmatic-Type"]).isEqualTo("random")
            }
        }

        @Test
        fun `a dynamically registered external example closes the client connection`() {
            val fixture = loadFixture()
            HttpStub(
                port = freePort(),
                features = listOf(fixture.feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(fixture.feature to fixture.scenarioStubs)),
            ).use { stub ->
                stub.setExpectation(resourceFile("openapi/terminate_connection/dynamic.json").readText())
                assertThat(readFirstResponseByte(
                    endpoint = stub.endPoint,
                    rawRequest = rawPost("/dynamic", """{"id":6,"name":"dynamic"}""")
                )).isEqualTo(-1)
            }
        }

        @Test
        fun `termination happens before response interceptors`() {
            var interceptorInvocations = 0
            loadedStub().use { stub ->
                stub.registerResponseInterceptor(object : ResponseInterceptor {
                    override val name: String = "must-not-run"
                    override fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse {
                        interceptorInvocations++
                        return httpResponse
                    }
                })

                assertThat(readFirstResponseByte(stub.endPoint, rawPost("/normal", """{"id":1,"name":"normal"}"""))).isEqualTo(-1)
            }

            assertThat(interceptorInvocations).isZero()
        }

        @Test
        fun `matching examples are prioritized transient then dynamic then static`() {
            val fixture = loadFixture()
            HttpStub(
                port = freePort(),
                features = listOf(fixture.feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(fixture.feature to fixture.scenarioStubs)),
            ).use { stub ->
                stub.setExpectation(resourceFile("openapi/terminate_connection/priority-dynamic.json").readText())
                stub.setExpectation(resourceFile("openapi/terminate_connection/priority-transient.json").readText())

                val requestBody = """{"id":7,"name":"priority"}"""
                assertThat(readFirstResponseByte(stub.endPoint, rawPost("/priority", requestBody))).isEqualTo(-1)
                assertThat(readFirstResponseByte(stub.endPoint, rawPost("/priority", requestBody))).isEqualTo(-1)

                val staticResponse = HttpClient(stub.endPoint).use { it.execute(postRequest("/priority", requestBody)) }
                assertThat(staticResponse.status).isEqualTo(200)
                assertThat(staticResponse.body).isEqualTo(parsedJSONObject("""{"source":"static"}"""))
            }
        }

        fun terminatingExamples(): Stream<Arguments> = Stream.of(
            Arguments.of("normal", "/normal", """{"id":1,"name":"normal"}"""),
            Arguments.of("partial", "/partial", """{"id":2,"name":"any value"}"""),
            Arguments.of("matcher exact", "/matcher-exact", """{"id":42,"name":"matcher"}"""),
            Arguments.of("pattern token", "/pattern-token", """{"id":3,"name":"different string"}"""),
            Arguments.of("matcher data type", "/matcher-data-type", """{"id":99,"name":"matcher"}"""),
        )

        private fun loadedStub(): HttpStub {
            val fixture = loadFixture()
            return HttpStub(
                port = freePort(),
                features = listOf(fixture.feature),
                rawHttpStubs = contractInfoToHttpExpectations(listOf(fixture.feature to fixture.scenarioStubs)),
            )
        }

        private fun loadFixture(): LoadedFixture {
            val specFile = resourceFile("openapi/terminate_connection/spec.yaml")
            val examplesDirectory = resourceFile("openapi/terminate_connection/spec_examples")
            val results = loadContractStubsFromFilesAsResults(
                strictMode = true,
                withImplicitStubs = false,
                specmaticConfig = SpecmaticConfig(),
                dataDirPaths = listOf(examplesDirectory.absolutePath),
                contractPathDataList = listOf(ContractPathData(baseDir = "", path = specFile.absolutePath)),
            )

            val failures = results.filterIsInstance<FeatureStubsResult.Failure>()
            assertThat(failures).isEmpty()

            val success = results.filterIsInstance<FeatureStubsResult.Success>().single()
            return LoadedFixture(success.feature, success.scenarioStubs)
        }

        private fun postRequest(path: String, body: String, parseJson: Boolean = true): HttpRequest = HttpRequest(
            path = path,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = if (parseJson) parsedJSONObject(body) else parsedValue(body),
        )

        private fun rawPost(path: String, body: String): String {
            return "POST $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n" +
                    "Connection: keep-alive\r\n\r\n" +
                    body
        }

        private fun resourceFile(path: String): File {
            return File(requireNotNull(javaClass.classLoader.getResource(path)).toURI())
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

    private fun readFirstResponseByte(endpoint: String, rawRequest: String): Int {
        val uri = URI(endpoint)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().bufferedWriter().apply {
                write(rawRequest)
                flush()
            }

            socket.getInputStream().read()
        }
    }

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

private data class LoadedFixture(val feature: Feature, val scenarioStubs: List<ScenarioStub>)
private object FakeMatcherEngine : MatcherEngine {
    override fun patternFrom(value: ScalarValue, originalPattern: Pattern, resolver: Resolver): Pattern = originalPattern
    override fun matchResponseValue(expectedValue: Value, actualValue: Value, resolver: Resolver): Result = Result.Success()
}

private object FakeHttpStubMatcherFactory : HttpStubMatcherFactory {
    override fun create(httpStubData: HttpStubData): HttpStubMatcher {
        val maxMatches = if (httpStubData.resolveOriginalRequest()?.path == "/matcher-times") 2 else 1
        var successfulMatches = 0

        return object : HttpStubMatcher {
            override fun matches(httpRequest: HttpRequest): Result {
                return if (successfulMatches < maxMatches) {
                    successfulMatches++
                    Result.Success()
                } else {
                    Result.Failure("matcher exhausted")
                }
            }

            override fun utilize(): Boolean {
                return httpStubData.stubToken != null && successfulMatches >= maxMatches
            }
        }
    }
}
