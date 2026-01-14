package io.specmatic.core

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.specmatic.DefaultStrategies
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import io.specmatic.toViolationReportString
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.TestExecutor
import io.mockk.every
import io.mockk.mockk
import io.specmatic.conversions.*
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.test.ScenarioAsTest
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import java.util.function.Consumer

internal class RunContractTestsUsingScenario {
    @Test
    fun `should generate one test scenario when there are no examples`() {
        val scenario = Scenario(
            "test",
            HttpRequestPattern(),
            HttpResponsePattern(),
            HashMap(),
            LinkedList(),
            HashMap(),
            HashMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        scenario.generateTestScenarios(DefaultStrategies).map { it.value }.let {
            assertThat(it.toList().size).isEqualTo(1)
        }
    }

    @Test
    fun `should generate two test scenarios when there are two rows in examples`() {
        val patterns = Examples(emptyList(), listOf(Row(), Row()))
        val scenario = Scenario(
            "test",
            HttpRequestPattern(),
            HttpResponsePattern(),
            HashMap(),
            listOf(patterns),
            HashMap(),
            HashMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )
        scenario.generateTestScenarios(DefaultStrategies).map { it.value }.let {
            assertThat(it.toList().size).isEqualTo(2)
        }
    }

    @Test
    fun `should not match when there is an Exception`() {
        val httpResponsePattern = mockk<HttpResponsePattern>(relaxed = true)
        every { httpResponsePattern.matchesResponse(any(), any()) }.throws(ContractException("message"))
        val scenario = Scenario(
            "test",
            HttpRequestPattern(),
            httpResponsePattern,
            HashMap(),
            LinkedList(),
            HashMap(),
            HashMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )
        scenario.matches(HttpResponse.EMPTY).let {
            assertThat(it is Result.Failure).isTrue()
            assertThat((it as Result.Failure).toMatchFailureDetails()).isEqualTo(MatchFailureDetails(listOf(), listOf("Exception: message")))
        }
    }

    @Test
    fun `given a pattern in an example, facts declare without a value should pick up the pattern`() {
        val row = Row(listOf("id"), listOf("(string)"))

        val newState = newExpectedServerStateBasedOn(row, mapOf("id" to True), HashMap(), Resolver())

        assertThat(newState.getValue("id").toStringLiteral()).isNotEqualTo("(string)")
        assertThat(newState.getValue("id").toStringLiteral().trim().length).isGreaterThan(0)
    }

    @Test
    fun `given a pattern in an example in a scenario generated based on a row, facts declare without a value should pick up the pattern`() {
        val row = Row(listOf("id"), listOf("(string)"))
        val example = Examples(listOf("id"), listOf(row))

        val state = HashMap(mapOf<String, Value>("id" to True))
        val scenario = Scenario(
            "Test",
            HttpRequestPattern(httpPathPattern = HttpPathPattern(emptyList(), path = "/")),
            HttpResponsePattern(status = 200),
            state,
            listOf(example),
            HashMap(),
            HashMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )

        val testScenarios = scenario.generateTestScenarios(DefaultStrategies).map { it.value }
        val newState = testScenarios.first().expectedFacts

        assertThat(newState.getValue("id").toStringLiteral()).isNotEqualTo("(string)")
        assertThat(newState.getValue("id").toStringLiteral().trim().length).isGreaterThan(0)
    }

    @Test
    fun `will not match a mock http request with unexpected request headers`() {
        val scenario = Scenario(
            "Test",
            HttpRequestPattern(method="GET", httpPathPattern = HttpPathPattern(emptyList(), "/"), headersPattern = HttpHeadersPattern(mapOf("X-Expected" to StringPattern()))),
            HttpResponsePattern(status = 200),
            emptyMap(),
            emptyList(),
            emptyMap(),
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )
        val mockRequest = HttpRequest(method = "GET", path = "/", headers = mapOf("X-Expected" to "value", "X-Unexpected" to "value"))
        val mockResponse = HttpResponse.OK

        assertThat(scenario.matchesMock(mockRequest, mockResponse)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `will not match a mock http request with unexpected response headers`() {
        val scenario = Scenario(
            "Test",
            HttpRequestPattern(method="GET", httpPathPattern = HttpPathPattern(emptyList(), "/"), headersPattern = HttpHeadersPattern(emptyMap())),
            HttpResponsePattern(status = 200, headersPattern = HttpHeadersPattern(mapOf("X-Expected" to StringPattern()))),
            emptyMap(),
            emptyList(),
            emptyMap(),
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )
        val mockRequest = HttpRequest(method = "GET", path = "/")
        val mockResponse = HttpResponse.OK.copy(headers = mapOf("X-Expected" to "value", "X-Unexpected" to "value"))

        assertThat(scenario.matchesMock(mockRequest, mockResponse)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `will not match a mock http request with unexpected query params`() {
        val scenario = Scenario(
            "Test",
            HttpRequestPattern(method="GET", httpPathPattern = HttpPathPattern(emptyList(), "/"), httpQueryParamPattern = HttpQueryParamPattern(mapOf("expected" to StringPattern())), headersPattern = HttpHeadersPattern(emptyMap(), null)),
            HttpResponsePattern(status = 200),
            emptyMap(),
            emptyList(),
            emptyMap(),
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )
        val mockRequest = HttpRequest(method = "GET", path = "/", queryParametersMap = mapOf("expected" to "value", "unexpected" to "value"))
        val mockResponse = HttpResponse.OK

        assertThat(scenario.matchesMock(mockRequest, mockResponse)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `will not match a mock json body with unexpected keys`() {
        val scenario = Scenario(
            "Test",
            HttpRequestPattern(method="POST", httpPathPattern = HttpPathPattern(emptyList(), "/"), httpQueryParamPattern = HttpQueryParamPattern(mapOf("expected" to StringPattern())), headersPattern = HttpHeadersPattern(emptyMap(), null), body = parsedPattern("""{"expected": "value"}""")),
            HttpResponsePattern(status = 200),
            emptyMap(),
            emptyList(),
            emptyMap(),
            emptyMap(),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        )
        val mockRequest = HttpRequest(method = "POST", path = "/", body = parsedValue("""{"unexpected": "value"}"""))
        val mockResponse = HttpResponse.OK

        assertThat(scenario.matchesMock(mockRequest, mockResponse)).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should mock a header with a pattern value`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When GET /resource
And request-header X-RequestKey (number)
Then status 200
And response-header X-ResponseKey (number)
        """.trim()

        val request = HttpRequest("GET", "/resource", mapOf("X-RequestKey" to "(number)"), EmptyString)
        val response = HttpResponse(200, "", mapOf("X-ResponseKey" to "(number)"))
        val stub = ScenarioStub(request, response)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("GET", "/resource", mapOf("X-RequestKey" to "10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertDoesNotThrow { matchingResponse.response.headers.getValue("X-ResponseKey").toInt() }
    }

    @Test
    fun `should mock a header with a primitive number`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When GET /resource
And request-header X-RequestKey (number)
Then status 200
And response-header X-ResponseKey (number)
        """.trim()

        val request = HttpRequest("GET", "/resource", mapOf("X-RequestKey" to "10"), EmptyString)
        val response = HttpResponse(200, "", mapOf("X-ResponseKey" to "20"))
        val stub = ScenarioStub(request, response)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("GET", "/resource", mapOf("X-RequestKey" to "10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.headers.getValue("X-ResponseKey")).isEqualTo("20")
    }

    @Test
    fun `should mock a query with a number type`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When GET /resource?query=(number)
Then status 200
        """.trim()

        val request = HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "(number)"))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a query with a primitive number`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When GET /resource?query=(number)
Then status 200
        """.trim()

        val request = HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "10"))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a query with a boolean type`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When GET /resource?query=(boolean)
Then status 200
        """.trim()

        val request = HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "(boolean)"))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        val result = requestPattern.matches(
            HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "true")),
            Resolver()
        )
        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a query with a primitive boolean`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When GET /resource?query=(boolean)
Then status 200
        """.trim()

        val request = HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "true"))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("GET", "/resource", queryParametersMap = mapOf("query" to "true")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a form field with a pattern value`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When POST /resource
And form-field value (number)
Then status 200
        """.trim()

        val request = HttpRequest("POST", "/resource", formFields = mapOf("value" to "(number)"))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("POST", "/resource", formFields = mapOf("value" to "10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a form field with a primitive value`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When POST /resource
And form-field value (number)
Then status 200
        """.trim()

        val request = HttpRequest("POST", "/resource", formFields = mapOf("value" to "10"))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("POST", "/resource", formFields = mapOf("value" to "10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a multipart part with a pattern value`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When POST /resource
And request-part value (number)
Then status 200
        """.trim()

        val request = HttpRequest("POST", "/resource", multiPartFormData = listOf(MultiPartContentValue("value", StringValue("(number)"))))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("POST", "/resource", multiPartFormData = listOf(MultiPartContentValue("value", StringValue("10")))), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a multipart part with a primitive value`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When POST /resource
And request-part value (number)
Then status 200
        """.trim()

        val request = HttpRequest("POST", "/resource", multiPartFormData = listOf(MultiPartContentValue("value", StringValue("10"))))
        val stub = ScenarioStub(request, HttpResponse.OK)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("POST", "/resource", multiPartFormData = listOf(MultiPartContentValue("value", StringValue("10")))), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertThat(matchingResponse.response.status).isEqualTo(200)
    }

    @Test
    fun `should mock a body with a primitive pattern`() {
        val gherkin = """Feature: Test API
Scenario: Test Scenario
When POST /resource
And request-body (number)
Then status 200
And response-body (number)
        """.trim()

        val request = HttpRequest("POST", "/resource", body = StringValue("(number)"))
        val response = HttpResponse(200, body = StringValue("(number)"))
        val stub = ScenarioStub(request, response)

        val feature = parseGherkinStringToFeature(gherkin)

        val requestPattern = request.toPattern()
        assertThat(requestPattern.matches(HttpRequest("POST", "/resource", body = StringValue("10")), Resolver())).isInstanceOf(Result.Success::class.java)

        val matchingResponse = feature.matchingStub(stub)
        assertDoesNotThrow { matchingResponse.response.body.toStringLiteral().toInt() }
    }

    @Test
    fun `should add bindings and variables if passed when generating test scenarios`() {
        val gherkin = """Feature: Test API
            Background:
                Given value auth from auth.spec

            Scenario: Test Scenario
                When GET /
                And request-header X-Header1 (string)
                And request-header X-Header2 (string)
                Then status 200
                And response-header X-Data (string)
                And export data = response-header.X-Data
                
                Examples:
                | X-Header1                   | X-Header2                         |
                | (${DEREFERENCE_PREFIX}data) | (${DEREFERENCE_PREFIX}auth.token) | 
                """.trim()

        val feature = parseGherkinStringToFeature(gherkin, "original.spec").copy(testVariables = mapOf("data" to "10"), testBaseURLs = mapOf("auth.spec" to "http://baseurl"))

        val mockCache = mockk<ContractCache>()
        every {
            mockCache.lookup(any())
        }.returns(mapOf("token" to "20"))

        val testScenarios = feature.scenarios.map { scenario ->
            val updatedReferences = scenario.references.mapValues {
                it.value.copy(contractCache = mockCache)
            }

            scenario.copy(references = updatedReferences).generateTestScenarios(
                DefaultStrategies,
                variables = mapOf("data" to "10"),
                testBaseURLs = mapOf("auth.spec" to "http://baseurl")
            ).map { it.value }.toList()
        }.flatten()

        assertThat(testScenarios).allSatisfy(Consumer {
            assertThat(it.bindings).isEqualTo(mapOf("data" to "response-header.X-Data"))

            assertThat((it.httpRequestPattern.headersPattern.pattern["X-Header1"] as ExactValuePattern).pattern.toStringLiteral()).isEqualTo("10")
            assertThat((it.httpRequestPattern.headersPattern.pattern["X-Header2"] as ExactValuePattern).pattern.toStringLiteral()).isEqualTo("20")
        })
    }

    @Test
    fun `mock should return match errors across both request and response`() {
        val requestType = HttpRequestPattern(method = "POST", httpPathPattern = buildHttpPathPattern("http://localhost/data"), body = JSONObjectPattern(mapOf("id" to NumberPattern())))
        val responseType = HttpResponsePattern(status = 200, body = JSONObjectPattern(mapOf("id" to NumberPattern())))

        val scenario = Scenario(ScenarioInfo("name", requestType, responseType,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI))

        val result = scenario.matchesMock(
            HttpRequest("POST", "/data", body = parsedJSON("""{"id": "abc123"}""")),
            HttpResponse.ok(parsedJSON("""{"id": "abc123"}"""))
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)

        result as Result.Failure

        assertThat(result.toMatchFailureDetailList()).hasSize(2)

        assertThat(result.reportString()).contains("REQUEST.BODY.id")
        assertThat(result.reportString()).contains("RESPONSE.BODY.id")
    }

    @Test
    fun `test erroneous contract test response should return customized error`() {
        val contract = OpenApiSpecification.fromYAML(
            """
openapi: 3.0.0
info:
  title: Sample API
  version: 0.1.9
paths:
  /data:
    post:
      summary: hello world
      description: test
      requestBody:
        content:
          application/json:
            examples:
              200_OK:
                value:
                  data: 10
            schema:
              type: object
              properties:
                data:
                  type: number
              required:
                - data
      responses:
        '200':
          description: Says hello
          content:
            text/plain:
              examples:
                200_OK:
                  value: 10
              schema:
                type: number
""".trimIndent(), ""
        ).toFeature()

        val contractTestScenarios = contract.generateContractTests(emptyList())

        val result: Result =
            contractTestScenarios.first().runTest(object : TestExecutor {
                        override fun execute(request: HttpRequest): HttpResponse {
                            return HttpResponse.ok("abc")
                        }

                        override fun setServerState(serverState: Map<String, Value>) {
                        }
                    }).result as Result.Failure

        assertThat(result.reportString()).contains("Specification expected")
        assertThat(result.reportString()).contains("response contained")
    }

    @Test
    fun `should follow the monitor link in the response header on accepted status code`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 202,
                headersPattern = HttpHeadersPattern(mapOf("Link" to StringPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val monitorScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/monitor/(id:number)"), method = "GET"),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(mapOf("request" to AnyNonNullJSONValue(), "response?" to AnyNonNullJSONValue()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario), protocol = SpecmaticProtocol.HTTP)
        val contractTest = ScenarioAsTest(acceptedScenario, feature, feature.flagsBased, originalScenario = postScenario,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)

        val (result, _) = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.method == "POST") {
                    return HttpResponse(202, headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor"))
                }

                return HttpResponse(
                    200,
                    body = parsedJSONObject("""
                        {
                            "request": {
                                "method": "POST",
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ]
                            },
                            "response": {
                                "statusCode": 201,
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ],
                                "body": { "name": "John", "age": 20 }
                            }
                        }
                        """.trimIndent())
                )
            }
        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should fail the test if accepted status code was return for a 201 response test`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 202,
                headersPattern = HttpHeadersPattern(mapOf("Link" to StringPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val monitorScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/monitor/(id:number)"), method = "GET"),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(mapOf("request" to AnyNonNullJSONValue(), "response?" to AnyNonNullJSONValue()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario), protocol = SpecmaticProtocol.HTTP)
        val contractTest = ScenarioAsTest(postScenario, feature, feature.flagsBased, originalScenario = postScenario,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)

        val (result, _) = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.method == "POST") {
                    return HttpResponse(202, headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor"))
                }

                return HttpResponse(
                    200,
                    body = parsedJSONObject("""
                        {
                            "request": {
                                "method": "POST",
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ]
                            },
                            "response": {
                                "statusCode": 201,
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ],
                                "body": { "name": "John", "age": 20 }
                            }
                        }
                        """.trimIndent())
                )
            }
        })

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST / -> 201
        >> RESPONSE.STATUS
        R0002: HTTP status mismatch
        Documentation: https://docs.specmatic.io/rules#r0002
        Summary: The HTTP status code does not match the expected status code defined in the specification
        Specification expected status 201 but response contained status 202
        """.trimIndent())
    }

    @Test
    fun `should return failure when the monitor link returns invalid response`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
            )
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 202,
                headersPattern = HttpHeadersPattern(mapOf("Link" to StringPattern()))
            )
        ))
        val monitorScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/monitor/(id:number)"), method = "GET"),
            httpResponsePattern = HttpResponsePattern(
                status = 200,
                body = JSONObjectPattern(mapOf("request" to AnyNonNullJSONValue(), "response?" to AnyNonNullJSONValue()))
            )
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, monitorScenario))
        val contractTest = ScenarioAsTest(acceptedScenario, feature, feature.flagsBased, originalScenario = postScenario)

        val (result, _) = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.method == "POST") {
                    return HttpResponse(202, headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor"))
                }

                return HttpResponse(
                    200,
                    body = parsedJSONObject("""
                        {
                            "request": {
                                "method": "POST",
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ]
                            },
                            "response": {
                                "statusCode": 201,
                                "header": [
                                    { "name": "Content-Type", "value": "application/json" }
                                ],
                                "body": { "name": 20, "age": "John" }
                            }
                        }
                        """.trimIndent())
                )
            }
        })
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST / -> 202
        
        ${
            toViolationReportString(
                breadCrumb = "MONITOR.RESPONSE.BODY.name",
                details = "Invalid request or response payload in the monitor response\n${DefaultMismatchMessages.typeMismatch("string", "20", "number")}",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "MONITOR.RESPONSE.BODY.age",
                details = "Invalid request or response payload in the monitor response\n${DefaultMismatchMessages.typeMismatch("number", "\"John\"", "string")}",
                StandardRuleViolation.TYPE_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should return failure when the monitor scenario doesn't exist`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 202,
                headersPattern = HttpHeadersPattern(mapOf("Link" to StringPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario), protocol = SpecmaticProtocol.HTTP)
        val contractTest = ScenarioAsTest(acceptedScenario, feature, feature.flagsBased, originalScenario = postScenario,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)

        val (result, response) = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(202, headers = mapOf("Link" to "</monitor/123>;rel=related;title=monitor"))
            }
        })
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST / -> 202
        No monitor scenario found matching link: Link(url=/monitor/123, rel=related, title=monitor)
        """.trimIndent())
    }

    @Test
    fun `should return the original failure when no monitor link is provided in the response headers`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(
                status = 201,
                body = JSONObjectPattern(mapOf("name" to StringPattern(), "age" to NumberPattern()))
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(httpPathPattern = buildHttpPathPattern("/"), method = "POST"),
            httpResponsePattern = HttpResponsePattern(status = 202),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario), protocol = SpecmaticProtocol.HTTP)
        val contractTest = ScenarioAsTest(postScenario, feature, feature.flagsBased, originalScenario = postScenario,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)

        val (result, response) = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(202)
            }
        })
        println(result.reportString())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        In scenario ""
        API: POST / -> 201
        ${
            toViolationReportString(
                breadCrumb = "RESPONSE.STATUS",
                details = SpecificationAndResponseMismatch.mismatchMessage("status 201", "status 202"),
                OpenApiRuleViolation.STATUS_MISMATCH
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should retry tooManyRequests response for a given scenario`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = 201),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        ))
        val tooManyRequestsScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = HttpStatusCode.TooManyRequests.value),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val contractTest = ScenarioAsTest(tooManyRequestsScenario, feature, feature.flagsBased, originalScenario = postScenario,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        val (result) = contractTest.runTest(object : TestExecutor {
            var retryCount: Int = 0

            override fun execute(request: HttpRequest): HttpResponse {
                if (++retryCount <= 3) return HttpResponse(429, headers = mapOf(HttpHeaders.RetryAfter to "0"))
                return HttpResponse(201)
            }
        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should fail a test if a tooManyRequests response was returned for a 201 test`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = 201),
        ))
        val tooManyRequestsScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = HttpStatusCode.TooManyRequests.value),
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, tooManyRequestsScenario))
        val contractTest = ScenarioAsTest(postScenario, feature, feature.flagsBased, originalScenario = postScenario)
        val (result) = contractTest.runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(429, headers = mapOf(HttpHeaders.RetryAfter to "0"))
            }
        })

        println(result.reportString())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("expected status 201 but response contained status 429")
    }

    @Test
    fun `should accept any 2xx response when the original scenarios was for tooManyRequests`() {
        val postScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = 201),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        ))
        val acceptedScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = 202),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        ))
        val tooManyRequestsScenario = Scenario(ScenarioInfo(
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/(id:string)"), method = "POST",
                body = JSONObjectPattern(mapOf("age" to NumberPattern()))
            ),
            httpResponsePattern = HttpResponsePattern(status = HttpStatusCode.TooManyRequests.value),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI,
        ))

        val feature = Feature(name = "", scenarios = listOf(postScenario, acceptedScenario, tooManyRequestsScenario), protocol = SpecmaticProtocol.HTTP)
        val contractTest = ScenarioAsTest(tooManyRequestsScenario, feature, feature.flagsBased, originalScenario = tooManyRequestsScenario,
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI)
        val (result) = contractTest.runTest(object : TestExecutor {
            var retryCount: Int = 0

            override fun execute(request: HttpRequest): HttpResponse {
                if (retryCount++ == 0) return HttpResponse(429, headers = mapOf(HttpHeaders.RetryAfter to "0"))
                return HttpResponse(202)
            }
        })

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @ParameterizedTest
    @MethodSource("io.specmatic.core.ScenarioTest#securitySchemaProvider")
    fun `should use security schema values from examples when provided`(securitySchema: OpenAPISecurityScheme) {
        val exampleRequest = securitySchema.addTo(HttpRequest("POST", "/"))
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                securitySchemes = listOf(securitySchema)
            ),
            httpResponsePattern = HttpResponsePattern(status = 200),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(Row(requestExample = exampleRequest))
                )
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val feature = Feature(name = "", scenarios = listOf(scenario), protocol = SpecmaticProtocol.HTTP)

        val extractValue: (HttpRequest) -> String = { it ->
            when(securitySchema) {
                is APIKeyInHeaderSecurityScheme -> it.headers.getValue(securitySchema.name)
                is APIKeyInQueryParamSecurityScheme -> it.queryParams.getValues(securitySchema.name).first()
                else -> it.headers.getValue(AUTHORIZATION)
            }
        }

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK.also {
                    val logs = listOf(request.toLogString(), it.toLogString())
                    println(logs.joinToString(separator = "\n\n"))

                    val result = securitySchema.matches(request, Resolver())
                    assertThat(result).isInstanceOf(Result.Success::class.java)

                    val schemesToCheck = when(securitySchema) {
                        is CompositeSecurityScheme -> securitySchema.schemes
                        else -> listOf(securitySchema)
                    }
                    assertThat(schemesToCheck).allSatisfy {
                        val actualValue = extractValue(request)
                        val expectedValue = extractValue(exampleRequest)
                        assertThat(actualValue).isEqualTo(expectedValue)
                    }
                }
            }
        })

        assertThat(results.results).allSatisfy { assertThat(it).isInstanceOf(Result.Success::class.java) }
    }

    @ParameterizedTest
    @MethodSource("io.specmatic.core.ScenarioTest#securitySchemaProvider")
    fun `should generate security schema values if missing from examples`(securitySchema: OpenAPISecurityScheme) {
        val scenario = Scenario(
            name = "SIMPLE POST",
            httpRequestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"), method = "POST",
                securitySchemes = listOf(securitySchema)
            ),
            httpResponsePattern = HttpResponsePattern(status = 200),
            examples = listOf(
                Examples(
                    emptyList(),
                    listOf(Row(requestExample = HttpRequest("POST", "/")))
                )
            ),
            protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
        )
        val feature = Feature(name = "", scenarios = listOf(scenario), protocol = SpecmaticProtocol.HTTP)

        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse.OK.also {
                    val logs = listOf(request.toLogString(), it.toLogString())
                    println(logs.joinToString(separator = "\n\n"))
                    val result = securitySchema.matches(request, Resolver())

                    assertThat(result).isInstanceOf(Result.Success::class.java)
                    assertThat(securitySchema.isInRequest(request, complete = true)).isTrue()
                }
            }
        })

        assertThat(results.results).allSatisfy { assertThat(it).isInstanceOf(Result.Success::class.java) }
    }
}
