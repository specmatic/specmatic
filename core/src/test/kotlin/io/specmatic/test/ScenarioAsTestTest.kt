package io.specmatic.test

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
import io.specmatic.core.Substitution
import io.specmatic.core.HttpQueryParamPattern
import io.specmatic.core.matchers.MatcherEngine
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedValue
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
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.fixtures.OpenAPIFixtureExecutor
import io.specmatic.test.fixtures.FixtureExecutionMetadata
import io.specmatic.test.fixtures.FixtureScenarioType
import io.specmatic.test.interceptor.ContractTestInterceptor
import io.specmatic.test.interceptor.InterceptResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
                mapOf(
                    OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name,
                    ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
                )
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor(body = "anything")).result
            }

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(ServiceLoaderTestFixtureExecutor.calls).containsExactly("before", "after")
            assertThat(ServiceLoaderTestFixtureExecutor.receivedContexts).containsExactly(
                FixtureExecutionMetadata(FixtureScenarioType.POSITIVE),
                FixtureExecutionMetadata(FixtureScenarioType.POSITIVE)
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["before", "after"])
        fun `passes external example data to fixture execution`(fixtureDiscriminatorKey: String) {
            ServiceLoaderTestFixtureExecutor.reset()
            val data = parsedJSONObject("""{"people":{"alice":{"name":"Alice"}}}""")
            val scenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        beforeFixtures = listOf(StringValue("before")),
                        afterFixtures = listOf(StringValue("after")),
                        data = data
                    )
                )
            )

            withServiceLoaderEntries(
                mapOf(OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name)
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor(body = "anything"))
            }

            assertThat(ServiceLoaderTestFixtureExecutor.receivedData[fixtureDiscriminatorKey]).isEqualTo(data)
        }

        @Test
        fun `request generation should be able to use before fixture substitution store`() {
            val substitution = SubstitutionImpl.empty()
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
            val result = withServiceLoaderEntries(
                mapOf(
                    OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name,
                    ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
                )
            ) {
                val test = scenarioAsTest(scenario = testScenario, originalScenario = originalScenario, substitution = substitution)
                test.runTest(fixedResponseExecutor(body = "anything"))
            }

            val headers = result.request?.headers.orEmpty()
            assertThat(headers["Authorization"]).isEqualTo("token-123")
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution).isNotNull
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(
                StringValue("$(SECRET)"), StringPattern(),
            )?.value
            ).isEqualTo(StringValue("token-123"))
        }

        @Test
        fun `after fixture should be able to use before and response substitution store`() {
            ServiceLoaderTestFixtureExecutor.reset()

            val substitution = SubstitutionImpl.empty()
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
                mapOf(
                    OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name,
                    ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
                )
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
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(StringValue("$(SECRET)"), StringPattern())?.value)
                .isEqualTo(StringValue("token-123"))
            assertThat(ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(StringValue("$(QUERY_ID)"), StringPattern())?.value)
                .isEqualTo(StringValue("query-456"))
        }

        @Test
        fun `passes negative fixture execution context for negative scenario`() {
            ServiceLoaderTestFixtureExecutor.reset()
            val exampleRow = Row(scenarioStub = ScenarioStub(id = "fixture-id", beforeFixtures = listOf(StringValue("before")), afterFixtures = listOf(StringValue("after"))))
            val scenario = scenario(status = 400, exampleRow = exampleRow,).copy(isNegative = true)
            withServiceLoaderEntries(
                mapOf(
                    OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name,
                    ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
                )
            ) {
                scenarioAsTest(scenario).runTest(fixedResponseExecutor(400, "anything"))
            }

            assertThat(ServiceLoaderTestFixtureExecutor.calls).containsExactly("before")
            assertThat(ServiceLoaderTestFixtureExecutor.receivedContexts)
                .containsExactly(FixtureExecutionMetadata(FixtureScenarioType.NEGATIVE))
        }

        @Test
        fun `filters fixtures based on scenario selector`() {
            ServiceLoaderTestFixtureExecutor.reset()
            val positiveScenario = scenario(
                Row(
                    scenarioStub = ScenarioStub(
                        id = "fixture-id",
                        beforeFixtures = listOf(
                            parsedValue("""{"name":"before-all","executeFor":{"scenarios":"all"}}"""),
                            parsedValue("""{"name":"before-negative","executeFor":{"scenarios":"negative"}}""")
                        ),
                        afterFixtures = listOf(
                            parsedValue("""{"name":"after-positive","executeFor":{"scenarios":"positive"}}"""),
                            parsedValue("""{"name":"after-negative","executeFor":{"scenarios":"negative"}}""")
                        )
                    )
                )
            )

            withServiceLoaderEntries(
                mapOf(
                    OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name,
                    ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
                )
            ) {
                scenarioAsTest(positiveScenario).runTest(fixedResponseExecutor(body = "anything"))
            }

            assertThat(ServiceLoaderTestFixtureExecutor.fixturesSeen).containsExactly(
                listOf(parsedValue("""{"name":"before-all","executeFor":{"scenarios":"all"}}""")),
                listOf(parsedValue("""{"name":"after-positive","executeFor":{"scenarios":"positive"}}"""))
            )
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

    @Nested
    inner class FixtureExecutionMetadataTest {
        @Test
        fun `should derive positive scenario type for positive scenario`() {
            val positiveContext = FixtureExecutionMetadata.from(scenario())
            assertThat(positiveContext.scenarioType).isEqualTo(FixtureScenarioType.POSITIVE)
        }

        @Test
        fun `should derive negative scenario type for negative scenario`() {
            val negativeContext = FixtureExecutionMetadata.from(scenario().copy(isNegative = true))
            assertThat(negativeContext.scenarioType).isEqualTo(FixtureScenarioType.NEGATIVE)
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
    fun `withRequestValidator should use custom validator instead of default request validation`() {
        val scenario = scenario()
        var validatorCalls = 0
        var executorCalls = 0

        val contractTest = scenarioAsTest(scenario).withRequestValidator(object : RequestValidator {
            override fun validate(feature: Feature, scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest): Result {
                validatorCalls += 1
                return Result.Failure("custom request validator failed")
            }
        })

        val executionResult = withServiceLoaderEntries(
            mapOf(ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name)
        ) {
            contractTest.runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    executorCalls += 1
                    return HttpResponse(status = 200, body = "ok")
                }
            })
        }

        assertThat(validatorCalls).isEqualTo(1)
        assertThat(executorCalls).isEqualTo(0)
        assertThat(executionResult.result).isInstanceOf(Result.Failure::class.java)
        assertThat(executionResult.result.reportString()).contains("custom request validator failed")
    }

    @Test
    fun `runTest should seed substitution data from example when available`() {
        ServiceLoaderTestFixtureExecutor.reset()
        val testScenario = scenario(
            exampleRow = Row(
                scenarioStub = ScenarioStub(
                    beforeFixtures = listOf(StringValue("before")),
                    data = parsedJSONObject(
                        """{"lookupData":{"dictionary":{"*":{"message":"from-example-data"}}}}"""
                    )
                )
            )
        )

        val executionResult = withServiceLoaderEntries(
            mapOf(
                OpenAPIFixtureExecutor::class.java to ServiceLoaderTestFixtureExecutor::class.java.name,
                ContractTestInterceptor::class.java to ServiceLoaderTestInterceptor::class.java.name
            )
        ) {
            val feature = Feature(name = "feature", scenarios = listOf(testScenario), protocol = SpecmaticProtocol.HTTP)
            ScenarioAsTest(
                feature = feature,
                scenario = testScenario,
                specType = SpecType.OPENAPI,
                flagsBased = feature.flagsBased,
                originalScenario = testScenario,
                protocol = SpecmaticProtocol.HTTP,
            ).runTest(fixedResponseExecutor(body = "ok"))
        }

        assertThat(
            ServiceLoaderTestFixtureExecutor.receivedSubstitution?.substitute(StringValue("$(lookupData.dictionary[ID].message)"))
        ).isEqualTo(HasValue(StringValue("from-example-data")))
        assertThat(executionResult.result).isInstanceOf(Result.Success::class.java)
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
    fun `runTest should pass external example data to matcher when positive scenario has response body example`() {
        val matcherEngine = mockk<MatcherEngine>()
        mockkObject(MatcherEngine.Companion)
        every { MatcherEngine.load() } returns matcherEngine
        every { matcherEngine.matchResponseValue(any(), any(), any(), any()) } returns Result.Success()

        val responseBody = parsedJSONObject("""{"id": 10}""")
        val data = parsedJSONObject("""{"people":{"alice":{"name":"Alice"}}}""")
        val scenario = Scenario(
            ScenarioInfo(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/resource"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    body = JSONObjectPattern(mapOf("id" to NumberPattern())),
                    headersPattern = HttpHeadersPattern(contentType = "application/json")
                ),
            )
        ).copy(
            exampleRow = Row(
                scenarioStub = ScenarioStub(
                    response = HttpResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = responseBody
                    ),
                    data = data
                )
            )
        )

        scenarioAsTest(scenario).runTest(
            fixedResponseExecutor(
                status = 200,
                body = """{"id": 10}""",
                headers = mapOf("Content-Type" to "application/json")
            )
        )

        verify(exactly = 1) { matcherEngine.matchResponseValue(responseBody, responseBody, any(), data) }
    }

    @Test
    fun `runTest should report positive response example matcher failures under response body`() {
        val matcherEngine = mockk<MatcherEngine>()
        mockkObject(MatcherEngine.Companion)
        every { MatcherEngine.load() } returns matcherEngine
        every { matcherEngine.matchResponseValue(any(), any(), any(), any()) } returns
            Result.Failure("matcher failed").breadCrumb("items[0]")

        val responseBody = parsedJSONObject("""{"items": [{"id": 10}]}""")
        val scenario = Scenario(
            ScenarioInfo(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/resource"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(
                    status = 200,
                    body = JSONObjectPattern(mapOf("items" to parsedValue("""[{"id": 10}]""").deepPattern())),
                    headersPattern = HttpHeadersPattern(contentType = "application/json")
                ),
            )
        ).copy(
            exampleRow = Row(
                scenarioStub = ScenarioStub(
                    response = HttpResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = responseBody
                    )
                )
            )
        )

        val executionResult = try {
            scenarioAsTest(scenario).runTest(
                fixedResponseExecutor(
                    status = 200,
                    body = """{"items": [{"id": 10}]}""",
                    headers = mapOf("Content-Type" to "application/json")
                )
            )
        } finally {
            unmockkObject(MatcherEngine.Companion)
        }

        assertThat(executionResult.result.reportString()).contains(">> RESPONSE.BODY.items[0]")
    }

    @Test
    fun `runTest should not call matcher when negative scenario has response body example`() {
        val matcherEngine = mockk<MatcherEngine>()
        mockkObject(MatcherEngine.Companion)
        every { MatcherEngine.load() } returns matcherEngine
        every { matcherEngine.matchResponseValue(any(), any(), any(), any()) } returns Result.Success()

        val scenario = Scenario(
            ScenarioInfo(
                specType = SpecType.OPENAPI,
                protocol = SpecmaticProtocol.HTTP,
                httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/resource"), method = "GET"),
                httpResponsePattern = HttpResponsePattern(
                    status = 400,
                    body = JSONObjectPattern(mapOf("error" to StringPattern())),
                    headersPattern = HttpHeadersPattern(contentType = "application/json")
                ),
            )
        ).copy(
            isNegative = true,
            exampleRow = Row(
                scenarioStub = ScenarioStub(
                    response = HttpResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = parsedJSONObject("""{"id": 10}""")
                    )
                )
            )
        )

        scenarioAsTest(scenario).runTest(
            fixedResponseExecutor(
                status = 400,
                body = """{"error": "bad request"}""",
                headers = mapOf("Content-Type" to "application/json")
            )
        )

        verify(exactly = 0) { matcherEngine.matchResponseValue(any(), any(), any(), any()) }
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
        substitution: Substitution = SubstitutionImpl.empty()
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
        executionMetadata: FixtureExecutionMetadata,
        substitution: Substitution,
        data: JSONObjectValue
    ): FixtureExecutionDetails {
        receivedData[fixtureDiscriminatorKey] = data
        val filteredFixtures = fixtures.filterFor(executionMetadata)
        calls.add(fixtureDiscriminatorKey)
        fixturesSeen.add(filteredFixtures)
        receivedContexts.add(executionMetadata)
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
        val fixturesSeen: MutableList<List<Value>> = mutableListOf()
        val receivedContexts: MutableList<FixtureExecutionMetadata> = mutableListOf()
        val receivedData: MutableMap<String, JSONObjectValue> = mutableMapOf()
        var receivedSubstitution: Substitution? = null

        fun reset() {
            calls.clear()
            fixturesSeen.clear()
            receivedContexts.clear()
            receivedData.clear()
            receivedSubstitution = null
        }
    }
}

private fun List<Value>.filterFor(context: FixtureExecutionMetadata): List<Value> {
    return filter { fixture ->
        when (fixture.fixtureScenarioSelector()) {
            null, "all" -> true
            "positive" -> context.scenarioType == FixtureScenarioType.POSITIVE
            "negative" -> context.scenarioType == FixtureScenarioType.NEGATIVE
            else -> error("Unexpected executeFor.scenarios value in test fixture")
        }
    }
}

private fun Value.fixtureScenarioSelector(): String? {
    return ((this as? io.specmatic.core.value.JSONObjectValue)
        ?.jsonObject
        ?.get("executeFor") as? io.specmatic.core.value.JSONObjectValue)
        ?.jsonObject
        ?.get("scenarios")
        ?.toStringLiteral()
        ?.lowercase()
}

class ServiceLoaderTestInterceptor : ContractTestInterceptor {
    override fun updateRequest(
        testScenario: Scenario,
        originalScenario: Scenario,
        httpRequest: HttpRequest,
        substitution: Substitution,
    ): InterceptResult<HttpRequest> {
        return InterceptResult.Processed(
            value = originalScenario.resolveRequestSubstitutions(httpRequest, substitution)
        )
    }

    override fun updateSubstitution(
        testScenario: Scenario,
        originalScenario: Scenario,
        httpResponse: HttpResponse,
        substitution: Substitution,
    ): InterceptResult<Substitution> {
        val example = testScenario.exampleRow?.scenarioStub ?: return InterceptResult.PassThrough
        return InterceptResult.Processed(
            value = HasValue(
                substitution.upsertStoreUsing(
                    runningValue = httpResponse.toJSON(),
                    originalValue = example.response().toJSON(),
                )
            )
        )
    }
}
