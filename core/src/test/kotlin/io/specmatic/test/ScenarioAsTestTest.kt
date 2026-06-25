package io.specmatic.test

import io.specmatic.core.Feature
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.core.BadRequestOrDefault
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.ScenarioInfo
import io.specmatic.core.Resolver
import io.specmatic.core.Substitution
import io.specmatic.core.HttpQueryParamPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.pattern.parsedJSONObject
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
import io.specmatic.core.substitution.SubstitutionImpl
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
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
        fun `request generation should be able to use before fixture substitution store`() {
            val substitution = SubstitutionImpl.empty(Resolver())
            val originalScenario = Scenario(
                ScenarioInfo(
                    specType = SpecType.OPENAPI,
                    protocol = SpecmaticProtocol.HTTP,
                    httpResponsePattern = HttpResponsePattern(status = 200, body = StringPattern()),
                    httpRequestPattern = HttpRequestPattern(
                        method = "GET",
                        httpPathPattern = buildHttpPathPattern("/findAvailableProducts"),
                        headersPattern = HttpHeadersPattern(mapOf("Authorization" to StringPattern())),
                        httpQueryParamPattern = HttpQueryParamPattern(mapOf("type" to QueryParameterScalarPattern(StringPattern()))),
                    ),
                )
            )

            val testScenario = Scenario(
                ScenarioInfo(
                    specType = SpecType.OPENAPI,
                    protocol = SpecmaticProtocol.HTTP,
                    httpResponsePattern = HttpResponsePattern(status = 200, body = StringPattern()),
                    httpRequestPattern = HttpRequestPattern(
                        method = "GET",
                        httpPathPattern = buildHttpPathPattern("/findAvailableProducts"),
                        headersPattern = HttpHeadersPattern(mapOf("Authorization" to ExactValuePattern(StringValue("$(SECRET)")))),
                        httpQueryParamPattern = HttpQueryParamPattern(mapOf("type" to QueryParameterScalarPattern(ExactValuePattern(StringValue("book"))))),
                    ),
                )
            ).copy(
                exampleRow = Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        beforeFixtures = listOf(StringValue("before"))
                    )
                )
            )

            ServiceLoaderTestFixtureExecutor.reset()
            val result = withServiceLoaderEntries(mapOf(OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name)) {
                val test = scenarioAsTest(scenario = testScenario, originalScenario = originalScenario, substitution = substitution)
                test.runTest(fixedResponseExecutor(body = "anything"))
            }

            val headers = result.request?.headers.orEmpty()
            assertThat(headers["Authorization"]).isEqualTo("token-123")
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution).isNotNull
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(
                StringValue("$(SECRET)"), StringPattern(), null)?.value
            ).isEqualTo(StringValue("token-123"))
        }

        @Test
        fun `after fixture should be able to use before and response substitution store`() {
            ServiceLoaderTestFixtureExecutor.reset()

            val substitution = SubstitutionImpl.empty(Resolver())
            val scenario = scenario(
                exampleRow = Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        request = HttpRequest(
                            method = "GET",
                            path = "/findAvailableProducts",
                            queryParametersMap = mapOf("type" to "book")
                        ),
                        response = HttpResponse(
                            status = 200,
                            headers = mapOf("X-Query-Id" to "(QUERY_ID:string)"),
                            body = parsedJSONObject("""{"product-queries": 1}""")
                        ),
                        beforeFixtures = listOf(StringValue("before")),
                        afterFixtures = listOf(StringValue("after"))
                    )
                )
            ).copy(
                httpRequestPattern = HttpRequestPattern(
                    method = "GET",
                    httpPathPattern = buildHttpPathPattern("/findAvailableProducts"),
                    httpQueryParamPattern = HttpQueryParamPattern(
                        mapOf("type" to QueryParameterScalarPattern(ExactValuePattern(StringValue("book"))))
                    )
                ),
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    headersPattern = HttpHeadersPattern(mapOf("X-Query-Id" to StringPattern())),
                    body = JSONObjectPattern(mapOf("product-queries" to NumberPattern()))
                )
            )

            val result = withServiceLoaderEntries(
                mapOf(OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name)
            ) {
                scenarioAsTest(scenario, substitution = substitution).runTest(
                    fixedResponseExecutor(
                        status = 200,
                        body = """{"product-queries": 1}""",
                        headers = mapOf("X-Query-Id" to "query-456")
                    )
                )
            }

            assertThat(result.result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestFixtureExecutor.calls).containsExactly("before", "after")
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution).isNotNull
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(StringValue("$(SECRET)"), StringPattern(), null)?.value)
                .isEqualTo(StringValue("token-123"))
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(StringValue("$(QUERY_ID)"), StringPattern(), null)?.value)
                .isEqualTo(StringValue("query-456"))
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

        val testResultRecord = requireNotNull(contractTest.testResultRecord(executionResult))
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

    @Test
    fun `testResultRecord should retain reasoning when contract test is generated from feature decision`() {
        val scenario = expectationScenario(status = 200, contentType = "application/json")
        val feature = Feature(name = "feature", scenarios = listOf(scenario), protocol = SpecmaticProtocol.HTTP)
        val reasoning = Reasoning(mainReason = TestExecutionReason.NO_EXAMPLE)
        val contractTest = feature.generateContractTestsWithDecision(
            originalScenarios = listOf(scenario),
            scenarios = sequenceOf(Decision.Execute(value = scenario, context = scenario, reasoning = reasoning))
        ).filterIsInstance<Decision.Execute<ContractTest, Scenario>>().single().value

        val executionResult = contractTest.runTest(fixedResponseExecutor(status = 200, body = "ok", headers = mapOf("Content-Type" to "application/json")))
        val testResultRecord = requireNotNull(contractTest.testResultRecord(executionResult))
        val ctrfReasons = requireNotNull(testResultRecord.extraFields().reasons)

        assertEquals(1, ctrfReasons.size)
        assertEquals(reasoning, testResultRecord.reasoning)
        assertEquals(TestExecutionReason.NO_EXAMPLE.id, ctrfReasons.single().id)
        assertEquals(TestExecutionReason.NO_EXAMPLE.title, ctrfReasons.single().title)
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

    private fun scenarioAsTest(
        scenario: Scenario,
        scenarios: List<Scenario> = listOf(scenario),
        originalScenario: Scenario = scenario,
        substitution: Substitution = SubstitutionImpl.empty(Resolver())
    ): ScenarioAsTest {
        val feature = Feature(name = "feature", scenarios = scenarios, protocol = SpecmaticProtocol.HTTP)
        return ScenarioAsTest(
            scenario = scenario,
            feature = feature,
            flagsBased = feature.flagsBased,
            originalScenario = originalScenario,
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI,
            substitution = substitution
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
    override fun execute(
        id: String,
        fixtures: List<Value>,
        fixtureDiscriminatorKey: String,
        substitution: Substitution
    ): FixtureExecutionDetails {
        calls.add(fixtureDiscriminatorKey)
        val updatedSubstitution = when (fixtureDiscriminatorKey) {
            "before" -> substitution.upsertStoreUsing(
                originalValue = StringValue("(SECRET:string)"),
                runningValue = StringValue("token-123")
            )
            else -> substitution
        }

        receivedSubstitution = updatedSubstitution
        return FixtureExecutionDetails(
            combinedResult = Result.Success(),
            updatedSubstitution = updatedSubstitution
        )
    }

    companion object {
        val calls: MutableList<String> = mutableListOf()
        var receivedSubstitution: Substitution? = null

        fun reset() {
            calls.clear()
            receivedSubstitution = null
        }
    }
}
