package io.specmatic.test

import io.specmatic.core.Feature
import io.specmatic.core.BadRequestOrDefault
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URLClassLoader
import java.nio.file.Files

class ScenarioAsTestTest {

    @Nested
    inner class FixtureExecutorTest {
        @Test
        fun `calls fixture executor from service loader when present`() {
            ServiceLoaderTestFixtureExecutor.reset()

            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        beforeFixtures = listOf(StringValue("before")),
                        afterFixtures = listOf(StringValue("after"))
                    )
                )
            )

            val result = withServiceLoaderEntries(
                mapOf(OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name)
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor(body = "anything")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestFixtureExecutor.calls).containsExactly("before", "after")
        }

        @Test
        fun `does not call fixture executor for negative scenario`() {
            ServiceLoaderTestFixtureExecutor.reset()
            val exampleRow = Row(scenarioStub = ScenarioStub(id = "fixture-id", beforeFixtures = listOf(StringValue("before")), afterFixtures = listOf(StringValue("after"))))
            val scenario = scenario(status = 400, exampleRow = exampleRow,).copy(isNegative = true)
            withServiceLoaderEntries(mapOf(OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name)) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor(400, "anything"))
            }

            assertThat(ServiceLoaderTestFixtureExecutor.calls).isEmpty()
        }

        @Test
        fun `does not call fixture executor when missing from service loader`() {
            ServiceLoaderTestFixtureExecutor.reset()

            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        beforeFixtures = listOf(StringValue("before")),
                        afterFixtures = listOf(StringValue("after"))
                    )
                )
            )

            val result = withServiceLoaderEntries(emptyMap()) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor(body = "anything")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestFixtureExecutor.calls).isEmpty()
        }
    }

    @Test
    fun `runTest with testExecutor should update negative scenario based on response and retain it in testResultRecord`() {
        val negativeScenario = negativeScenario(
            expectedResponses = mapOf(
                400 to listOf(
                    expectationScenario(status = 400, contentType = "application/json"),
                    expectationScenario(status = 400, contentType = "application/xml")
                )
            )
        )

        val testExecutor = fixedResponseExecutor(status = 400, body = "response", headers = mapOf("Content-Type" to "application/xml"))
        val executionResult = scenarioAsTest(negativeScenario).runTest(testExecutor)
        val updatedScenario = executionResult.result.scenario as Scenario
        val testResultRecord = scenarioAsTest(negativeScenario).testResultRecord(executionResult)
        val scenarioInRecord = testResultRecord.scenarioResult?.scenario as Scenario

        assertThat(updatedScenario.httpResponsePattern.headersPattern.contentType).isEqualTo("application/xml")
        assertThat(scenarioInRecord.httpResponsePattern.headersPattern.contentType).isEqualTo("application/xml")
    }

    @Test
    fun `runTest with base url should update negative scenario based on response and retain it in testResultRecord`() {
        val negativeScenario = negativeScenario(
            expectedResponses = mapOf(
                422 to listOf(
                    expectationScenario(status = 422, contentType = "application/json"),
                    expectationScenario(status = 422, contentType = "application/xml")
                )
            )
        )

        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/resource") {
                    call.respondText(
                        text = "response",
                        contentType = ContentType.parse("application/xml"),
                        status = HttpStatusCode.UnprocessableEntity
                    )
                }
            }
        }

        server.start(wait = false)
        try {
            val contractTest = scenarioAsTest(negativeScenario)
            val executionResult = contractTest.runTest("http://localhost:$port", 5000)
            val updatedScenario = executionResult.result.scenario as Scenario
            val testResultRecord = contractTest.testResultRecord(executionResult)
            val scenarioInRecord = testResultRecord.scenarioResult?.scenario as Scenario

            assertThat(updatedScenario.status).isEqualTo(422)
            assertThat(updatedScenario.isNegative).isTrue
            assertThat(updatedScenario.httpResponsePattern.headersPattern.contentType).isEqualTo("application/xml")
            assertThat(scenarioInRecord.httpResponsePattern.headersPattern.contentType).isEqualTo("application/xml")
        } finally {
            server.stop(1000, 1000)
        }
    }

    @Test
    fun `runTest should choose default response with matching content type when same-status content type does not match`() {
        val negativeScenario = negativeScenario(
            expectedResponses = mapOf(400 to listOf(expectationScenario(status = 400, contentType = "text/plain"))),
            defaultResponses = listOf(expectationScenario(status = 1000, contentType = "application/xml"))
        )

        val testExecutor = fixedResponseExecutor(status = 400, body = "response", headers = mapOf("Content-Type" to "application/xml"))
        val executionResult = scenarioAsTest(negativeScenario).runTest(testExecutor)
        val updatedScenario = executionResult.result.scenario as Scenario
        val testResultRecord = scenarioAsTest(negativeScenario).testResultRecord(executionResult)
        val scenarioInRecord = testResultRecord.scenarioResult?.scenario as Scenario

        assertThat(updatedScenario.statusInDescription).isEqualTo("1000")
        assertThat(scenarioInRecord.statusInDescription).isEqualTo("1000")
        assertThat(updatedScenario.httpResponsePattern.headersPattern.contentType).isEqualTo("application/xml")
    }

    @Test
    fun `testResultRecord should mark response as in specification when another feature scenario matches it`() {
        val scenario = expectationScenario(status = 200, contentType = "application/json")
        val alternativeScenario = expectationScenario(status = 400, contentType = "application/xml")
        val allScenarios = listOf(scenario, alternativeScenario)
        val contractTest = scenarioAsTest(scenario, allScenarios)
        val executionResult = contractTest.runTest(
            fixedResponseExecutor(
                status = 400,
                body = "response",
                headers = mapOf("Content-Type" to "application/xml")
            )
        )

        val testResultRecord = contractTest.testResultRecord(executionResult)
        assertThat(testResultRecord.result).isEqualTo(io.specmatic.reporter.model.TestResult.Failed)
        assertThat(testResultRecord.isResponseInSpecification).isTrue()
    }

    @Test
    fun `testResultRecord should mark response as outside specification when no feature scenario matches it`() {
        val scenario = expectationScenario(status = 200, contentType = "application/json")
        val contractTest = scenarioAsTest(scenario)
        val executionResult = contractTest.runTest(
            fixedResponseExecutor(
                status = 500,
                body = "response",
                headers = mapOf("Content-Type" to "application/xml")
            )
        )

        val testResultRecord = contractTest.testResultRecord(executionResult)
        assertThat(testResultRecord.result).isEqualTo(io.specmatic.reporter.model.TestResult.Failed)
        assertThat(testResultRecord.isResponseInSpecification).isFalse()
    }

    private fun scenario(exampleRow: Row? = null, status: Int = 200): Scenario {
        return Scenario(
            ScenarioInfo(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/resource"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = status, body = StringPattern()),
            )
        ).copy(exampleRow = exampleRow)
    }

    private fun expectationScenario(status: Int, contentType: String): Scenario {
        return Scenario(
            ScenarioInfo(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/resource"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(status = status, body = StringPattern(), headersPattern = HttpHeadersPattern(contentType = contentType)),
            )
        )
    }

    private fun negativeScenario(expectedResponses: Map<Int, List<Scenario>>, defaultResponses: List<Scenario> = emptyList()): Scenario {
        val baseScenario = expectationScenario(status = 200, contentType = "application/json")
        return baseScenario.copy(
            isNegative = true,
            badRequestOrDefault = BadRequestOrDefault(badRequestResponses = expectedResponses, defaultResponses = defaultResponses)
        )
    }

    private fun scenarioAsTest(scenario: Scenario, scenarios: List<Scenario> = listOf(scenario)): ScenarioAsTest {
        val feature = Feature(name = "feature", scenarios = scenarios, protocol = SpecmaticProtocol.HTTP)
        return ScenarioAsTest(
            scenario = scenario,
            feature = feature,
            flagsBased = feature.flagsBased,
            originalScenario = scenario,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )
    }

    private fun fixedResponseExecutor(status: Int = 200, body: String, headers: Map<String, String> = emptyMap()): TestExecutor {
        return object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(status = status, body = body, headers = headers)
            }
        }
    }

    private fun <T> withServiceLoaderEntries(entries: Map<Class<*>, String>, block: () -> T): T {
        val previousContextClassLoader = Thread.currentThread().contextClassLoader
        val tempDir = Files.createTempDirectory("specmatic-service-loader-test")
        val servicesDir = tempDir.resolve("META-INF/services")
        Files.createDirectories(servicesDir)

        entries.forEach { (service, implementationClassName) ->
            val serviceFile = servicesDir.resolve(service.name)
            Files.writeString(serviceFile, "$implementationClassName\n")
        }

        val classLoader = URLClassLoader(arrayOf(tempDir.toUri().toURL()), previousContextClassLoader)
        Thread.currentThread().contextClassLoader = classLoader

        return try {
            block()
        } finally {
            Thread.currentThread().contextClassLoader = previousContextClassLoader
            classLoader.close()
            tempDir.toFile().deleteRecursively()
        }
    }
}

class ServiceLoaderTestFixtureExecutor : OpenAPIFixtureExecutor {
    override fun execute(id: String, fixtures: List<Value>, fixtureDiscriminatorKey: String): Result {
        calls.add(fixtureDiscriminatorKey)
        return Result.Success()
    }

    companion object {
        val calls: MutableList<String> = mutableListOf()

        fun reset() {
            calls.clear()
        }
    }
}
