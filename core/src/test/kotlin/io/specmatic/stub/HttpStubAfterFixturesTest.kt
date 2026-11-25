package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.ctrf.model.FixtureExecutionResult
import io.specmatic.stub.listener.MockEvent
import io.specmatic.stub.listener.MockEventListener
import io.specmatic.test.FixtureExecutionDetails
import io.specmatic.test.HttpClient
import io.specmatic.test.TestResultRecord
import io.specmatic.test.fixtures.FixtureExecutionMetadata
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.Socket
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Execution(ExecutionMode.SAME_THREAD)
internal class HttpStubAfterFixturesTest {
    private val calls = LinkedBlockingQueue<FixtureCall>()
    private val events = LinkedBlockingQueue<MockEvent>()

    @Nested
    inner class Consumption {
        @Test
        fun `runs after fixtures for the selected example on each consumption`() {
            val examples = listOf(example("reusable"), example("unselected"), example("one-shot", stubToken = "one-shot"))
            val selections = listOf("one-shot", "reusable", "reusable")
            withStub(
                examples,
                afterClose = { stub, _ ->
                    val records = stub.ctrfTestResultRecords()
                    assertThat(records.map { it.exampleId }).containsExactlyElementsOf(selections)
                    assertThat(records.map(::fixtureResults))
                        .containsExactlyElementsOf(selections.map { listOf(success) })
                    assertThat(calls.toList()).containsExactlyInAnyOrderElementsOf(selections.map { id ->
                        FixtureCall(id, "after", mockFixtures, StringValue("order-123"))
                    })
                },
            ) { _, client ->
                selections.forEach { executeRequest(client, it) }
            }
        }
    }

    @Nested
    inner class NoFixtureExecution {
        @Test
        fun `serves a generated response without fixture results when there is no example`() {
            withStub(
                examples = emptyList(),
                afterClose = { stub, _ ->
                    val record = stub.ctrfTestResultRecords().single()
                    assertThat(record.exampleId).isNull()
                    assertThat(fixtureResults(record)).isNull()
                    assertThat(calls).isEmpty()
                },
            ) { _, client ->
                assertThat(client.execute(request).status).isEqualTo(200)
                events.awaitNext("mock response event")
            }
        }

        @Test
        fun `serves an example without fixture results when there is no executor`() {
            withStub(
                examples = listOf(example("no executor")),
                installProvider = false,
                afterClose = { stub, _ ->
                    val record = stub.ctrfTestResultRecords().single()
                    assertThat(record.exampleId).isEqualTo("no executor")
                    assertThat(fixtureResults(record)).isNull()
                    assertThat(calls).isEmpty()
                },
            ) { _, client ->
                executeRequest(client, "no executor")
            }
        }
    }

    @Nested
    inner class ResponseLifecycle {
        @Test
        fun `runs and reports after fixtures when the example terminates the connection`() {
            val example = example("terminating").copy(terminateConnection = true)
            withStub(
                examples = listOf(example),
                afterClose = { stub, _ ->
                    assertThat(calls.toList())
                        .containsExactly(FixtureCall("terminating", "after", mockFixtures, StringValue("order-123")))

                    val record = stub.ctrfTestResultRecords().single()
                    assertThat(record.exampleId).isEqualTo("terminating")
                    assertThat(record.connectionTerminated).isTrue()
                    assertThat(fixtureResults(record)).containsExactly(success)
                },
            ) { stub, _ ->
                val endpoint = URI(stub.endPoint)
                Socket(endpoint.host, endpoint.port).use { socket ->
                    socket.soTimeout = 5_000
                    val rawRequest = "GET /orders HTTP/1.1\r\nHost: ${endpoint.rawAuthority}\r\nX-Order: order-123\r\n\r\n"
                    socket.getOutputStream().write(rawRequest.toByteArray(Charsets.US_ASCII))
                    assertThat(socket.getInputStream().read()).isEqualTo(-1)
                }
            }
        }

        @Test
        fun `binds fixtures to the response after interception`() {
            val example = example("intercepted").copy(response = HttpResponse.ok(StringValue("Order: (ORDER:string)")))
            withStub(
                examples = listOf(example),
                afterClose = { _, _ ->
                    assertThat(calls.map { it.order }).containsExactly(StringValue("after interception"))
                },
            ) { stub, client ->
                stub.registerResponseInterceptor(object : ResponseInterceptor {
                    override val name = "Replace response body"
                    override fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse {
                        return httpResponse.copy(body = StringValue("Order: after interception"))
                    }
                })

                executeRequest(client, "Order: after interception", loggedBody = "Order: (ORDER:string)")
            }
        }
    }

    @Nested
    inner class CtrfSnapshots {
        @Test
        fun `answers while fixtures are pending and includes results only in later snapshots`() {
            val release = CountDownLatch(1)
            lateinit var pending: TestResultRecord
            withStub(
                examples = listOf(example("pending")),
                executeFixture = {
                    release.await()
                    success
                },
                afterClose = { stub, _ ->
                    val completed = stub.ctrfTestResultRecords().single()
                    assertThat(fixtureResults(pending)).isNull()
                    assertThat(completed.id).isEqualTo(pending.id)
                    assertThat(fixtureResults(completed)).containsExactly(success)
                },
            ) { stub, client ->
                try {
                    executeRequest(client, "pending")
                    calls.awaitNext("fixture invocation")
                    pending = stub.ctrfTestResultRecords().single()
                    assertThat(fixtureResults(pending)).isNull()
                } finally {
                    release.countDown()
                }
            }
        }

        @Test
        fun `shutdown reports a fixture still running after the report deadline`() {
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)
            try {
                withStub(
                    examples = listOf(example("pending at shutdown")),
                    executeFixture = {
                        started.countDown()
                        try {
                            release.await()
                            success
                        } finally {
                            finished.countDown()
                        }
                    },
                    afterClose = { _, directory ->
                        val report = directory.resolve("stub/html/index.html")
                        assertThat(Files.readString(report))
                            .contains("After fixtures were still running when the mock report was generated")
                    },
                ) { stub, client ->
                    executeRequest(client, "pending at shutdown")
                    calls.awaitNext("fixture invocation")
                    assertThat(fixtureResults(stub.ctrfTestResultRecords().single())).isNull()
                }
            } finally {
                release.countDown()
                if (started.count == 0L) {
                    check(finished.await(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Fixture did not exit after release" }
                }
            }
        }

        @ParameterizedTest
        @EnumSource(FailureMode::class)
        fun `reports fixture failures on the successful request record`(mode: FailureMode) {
            withStub(
                executeFixture = { call -> if (call.exampleId == "fallback") success else mode.execute() },
                examples = listOf(example("failing", stubToken = "failing"), example("fallback")),
                afterClose = { stub, directory ->
                    val records = stub.ctrfTestResultRecords()
                    assertThat(records.map { it.exampleId to it.actualResponseStatus })
                        .containsExactly("failing" to 200, "fallback" to 200)
                    assertThat(records.map(::fixtureResults))
                        .containsExactly(listOf(mode.expected), listOf(success))
                    assertThat(calls.map { it.exampleId }).containsExactlyInAnyOrder("failing", "fallback")
                    assertThat(Files.readString(directory.resolve("stub/html/index.html")))
                        .contains(mode.expected.errorMessage)
                },
            ) { _, client ->
                executeRequest(client, "failing")
                executeRequest(client, "fallback")
            }
        }
    }

    enum class FailureMode(val expected: FixtureResult) {
        RETURNED(FixtureResult("1", "after", "Fixture returned a failure")),
        THROWN(FixtureResult("", "after", "Fixture provider threw"));

        fun execute(): FixtureResult = when (this) {
            RETURNED -> expected
            THROWN -> error(checkNotNull(expected.errorMessage))
        }
    }

    private fun withStub(
        examples: List<ScenarioStub>,
        executeFixture: (FixtureCall) -> FixtureResult = { success },
        afterClose: (HttpStub, Path) -> Unit = { _, _ -> },
        installProvider: Boolean = true,
        test: (HttpStub, HttpClient) -> Unit,
    ) {
        val previousClassLoader = Thread.currentThread().contextClassLoader
        val repository = generateSequence(Path.of("").toAbsolutePath(), Path::getParent).first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val directory = Files.createTempDirectory(Files.createDirectories(repository.resolve("temp")), "stub-fixtures-")

        try {
            if (installProvider) {
                val services = Files.createDirectories(directory.resolve("META-INF").resolve("services"))
                Files.writeString(services.resolve(OpenAPIFixtureExecutor::class.java.name), TestFixtureExecutor::class.java.name)
            }

            URLClassLoader(arrayOf(directory.toUri().toURL()), previousClassLoader).use { classLoader ->
                Thread.currentThread().contextClassLoader = classLoader
                val listener = object : MockEventListener {
                    override fun onRespond(data: MockEvent) {
                        events.add(data)
                    }
                }

                val config = FixtureTestConfig(directory) { call ->
                    calls.add(call)
                    executeFixture(call)
                }

                val contractFile = Files.writeString(directory.resolve("orders.yaml"), contractYaml)
                val feature = OpenApiSpecification.fromYAML(contractYaml, contractFile.toString()).toFeature()
                    .copy(specmaticConfig = config)

                val stub = HttpStub(
                    port = testPort(),
                    features = listOf(feature),
                    listeners = listOf(listener),
                    specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(config),
                    rawHttpStubs = contractInfoToHttpExpectations(listOf(feature to examples)),
                )

                stub.use { stub -> HttpClient(stub.endPoint).use { client -> test(stub, client) } }
                afterClose(stub, directory)
            }
        } finally {
            Thread.currentThread().contextClassLoader = previousClassLoader
            directory.toFile().deleteRecursively()
        }
    }

    private fun example(id: String, stubToken: String? = null) = ScenarioStub(
        id = id,
        stubToken = stubToken,
        response = HttpResponse.ok(StringValue(id)),
        afterFixtures = mockFixtures,
        beforeFixtures = listOf(StringValue("before")),
        request = request.copy(headers = mapOf("X-Order" to "(ORDER:string)")),
    )

    private fun executeRequest(client: HttpClient, id: String, loggedBody: String = id) {
        val response = client.execute(request)
        assertThat(response.status).isEqualTo(200)
        assertThat(response.body).isEqualTo(StringValue(id))

        val event = events.awaitNext("mock response for '$id'")
        assertThat(event.response?.body).isEqualTo(StringValue(loggedBody))
    }

    private fun <T : Any> LinkedBlockingQueue<T>.awaitNext(description: String): T {
        return checkNotNull(poll(SIGNAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "No $description received" }
    }

    private fun fixtureResults(record: TestResultRecord): List<FixtureResult>? {
        return record.extraFields().afterFixtureExecutionResults?.map {
            FixtureResult(
                fixtureKind = it.fixtureKind,
                errorMessage = it.errorMessage,
                fixtureNumber = it.fixtureNumber,
            )
        }
    }

    data class FixtureResult(
        override val fixtureNumber: String,
        override val fixtureKind: String,
        override val errorMessage: String? = null,
    ) : FixtureExecutionResult

    private data class FixtureCall(
        val exampleId: String,
        val phase: String,
        val fixtures: List<Value>,
        val order: Value,
    )

    private class FixtureTestConfig(
        private val directory: Path,
        val execute: (FixtureCall) -> FixtureResult,
    ) : SpecmaticConfig by SpecmaticConfig() {
        override fun getReportDirPath(suffix: String?): Path = suffix?.let(directory::resolve) ?: directory
    }

    class TestFixtureExecutor : OpenAPIFixtureExecutor {
        override fun execute(
            id: String,
            fixtures: List<Value>,
            fixtureDiscriminatorKey: String,
            executionMetadata: FixtureExecutionMetadata,
            substitution: Substitution,
            data: JSONObjectValue,
        ): FixtureExecutionDetails {
            val order = substitution.substitute(StringValue("$(ORDER)"), StringPattern()).value
            val config = executionMetadata.specmaticConfig as FixtureTestConfig
            val fixtureResult = config.execute(FixtureCall(id, fixtureDiscriminatorKey, fixtures, order))
            val result = fixtureResult.errorMessage?.let { Result.Failure(it) } ?: Result.Success()
            return FixtureExecutionDetails(result, substitution, listOf(fixtureResult))
        }
    }

    companion object {
        private const val SIGNAL_TIMEOUT_SECONDS = 60L
        private val success = FixtureResult("1", "after")
        private val mockFixtures = ScenarioStub.parse("""
        {
          "http-request": {"method": "GET", "path": "/orders"},
          "http-response": {"status": 200},
          "after": [{"type": "mock", "id": "notifications", "exampleIds": ["order-created"]}]
        }
        """.trimIndent()).afterFixtures
        private val request = HttpRequest(method = "GET", path = "/orders", headers = mapOf("X-Order" to "order-123"))
        private val contractYaml = """
            openapi: 3.0.3
            info:
              title: Orders
              version: "1.0"
            paths:
              /orders:
                get:
                  parameters:
                    - name: X-Order
                      in: header
                      required: true
                      schema:
                        type: string
                  responses:
                    "200":
                      description: Order
                      content:
                        text/plain:
                          schema:
                            type: string
            """.trimIndent()
    }
}
