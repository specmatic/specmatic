package io.specmatic.stub

import io.mockk.every
import io.mockk.mockk
import io.specmatic.conversions.APIKeyInHeaderSecurityScheme
import io.specmatic.conversions.APIKeyInQueryParamSecurityScheme
import io.specmatic.conversions.CompositeSecurityScheme
import io.specmatic.conversions.OpenAPISecurityScheme
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpHeadersPattern
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.ACCEPT
import io.specmatic.core.Scenario
import io.specmatic.core.buildHttpPathPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ThreadSafeListOfStubsTest {
    @Test
    fun `matching transient stubs should prefer a successful example with a complete security requirement`() {
        val unsecured = stubWithAuthoredRequest("unsecured", HttpRequest("GET", "/products"))
        val secured = stubWithAuthoredRequest(
            "secured",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token"))
        )
        val expectations = ThreadSafeListOfStubs(mutableListOf(secured, unsecured), emptyMap())

        val result = expectations.matchingTransientStub(requestWithSecurityHeader())

        assertThat(result?.first).isEqualTo(secured)
    }

    @Test
    fun `matching dynamic stubs should prefer a successful example with a complete security requirement`() {
        val unsecured = stubWithAuthoredRequest("unsecured", HttpRequest("GET", "/products"))
        val secured = stubWithAuthoredRequest(
            "secured",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token"))
        )
        val expectations = ThreadSafeListOfStubs(mutableListOf(unsecured, secured), emptyMap())

        val result = expectations.matchingDynamicStub(requestWithSecurityHeader())

        assertThat(result.first).isEqualTo(secured)
    }

    @Test
    fun `matching static stubs should prefer a successful example with a complete security requirement`() {
        val unsecured = stubWithAuthoredRequest("unsecured", HttpRequest("GET", "/products"))
        val secured = stubWithAuthoredRequest(
            "secured",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token"))
        )
        val expectations = ThreadSafeListOfStubs(mutableListOf(unsecured, secured), emptyMap())

        val result = expectations.matchingStaticStub(requestWithSecurityHeader())

        assertThat(result.first).isEqualTo(secured)
    }

    @Test
    fun `multiple complete security examples should retain each matcher selection order`() {
        val first = stubWithAuthoredRequest(
            "first",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token"))
        )
        val second = stubWithAuthoredRequest(
            "second",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token"))
        )
        val expectations = ThreadSafeListOfStubs(mutableListOf(first, second), emptyMap())

        val transient = expectations.matchingTransientStub(requestWithSecurityHeader())
        val dynamic = expectations.matchingDynamicStub(requestWithSecurityHeader())
        val static = expectations.matchingStaticStub(requestWithSecurityHeader())

        assertThat(transient?.first).isEqualTo(second)
        assertThat(dynamic.first).isEqualTo(first)
        assertThat(static.first).isEqualTo(first)
    }

    @Test
    fun `partial and absent security requirements should have the same fallback priority`() {
        val partial = stubWithAuthoredRequest(
            "partial",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token")),
            securitySchemes = listOf(
                CompositeSecurityScheme(
                    listOf(
                        APIKeyInHeaderSecurityScheme(SECURITY_HEADER, null),
                        APIKeyInQueryParamSecurityScheme(SECURITY_QUERY_PARAMETER, null)
                    )
                )
            )
        )
        val unsecured = stubWithAuthoredRequest("unsecured", HttpRequest("GET", "/products"))
        val partialFirst = ThreadSafeListOfStubs(mutableListOf(partial, unsecured), emptyMap())
        val unsecuredFirst = ThreadSafeListOfStubs(mutableListOf(unsecured, partial), emptyMap())

        val partialFirstResult = partialFirst.matchingDynamicStub(requestWithSecurityHeader())
        val unsecuredFirstResult = unsecuredFirst.matchingDynamicStub(requestWithSecurityHeader())

        assertThat(partialFirstResult.first).isEqualTo(partial)
        assertThat(unsecuredFirstResult.first).isEqualTo(unsecured)
    }

    @Test
    fun `a composite security requirement should be complete only when every member is authored`() {
        val securitySchemes = listOf(
            CompositeSecurityScheme(
                listOf(
                    APIKeyInHeaderSecurityScheme(SECURITY_HEADER, null),
                    APIKeyInQueryParamSecurityScheme(SECURITY_QUERY_PARAMETER, null)
                )
            )
        )
        val partial = stubWithAuthoredRequest(
            "partial",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "matching-token")),
            securitySchemes = securitySchemes
        )
        val complete = stubWithAuthoredRequest(
            "complete",
            HttpRequest(
                "GET",
                "/products",
                headers = mapOf(SECURITY_HEADER to "matching-token"),
                queryParametersMap = mapOf(SECURITY_QUERY_PARAMETER to "matching-query-token")
            ),
            securitySchemes = securitySchemes
        )

        assertThat(partial.hasCompleteAuthoredSecurityRequirement()).isFalse()
        assertThat(complete.hasCompleteAuthoredSecurityRequirement()).isTrue()
    }

    @Test
    fun `a generated security header should not count as an authored security requirement`() {
        val generatedSecurityRequest = HttpRequest("GET", "/products")
            .addSecurityHeader(SECURITY_HEADER, "generated-token")
        val stub = stubWithAuthoredRequest("generated", generatedSecurityRequest)

        assertThat(stub.hasCompleteAuthoredSecurityRequirement()).isFalse()
    }

    @Test
    fun `a complete security example with a mismatched credential should not be preferred`() {
        val unsecured = stubWithAuthoredRequest("unsecured", HttpRequest("GET", "/products"))
        val secured = stubWithAuthoredRequest(
            "secured",
            HttpRequest("GET", "/products", headers = mapOf(SECURITY_HEADER to "expected-token")),
            requestType = matchingRequestPattern(
                mapOf(SECURITY_HEADER to ExactValuePattern(StringValue("expected-token")))
            )
        )
        val expectations = ThreadSafeListOfStubs(mutableListOf(secured, unsecured), emptyMap())

        val result = expectations.matchingDynamicStub(requestWithSecurityHeader())

        assertThat(result.first).isEqualTo(unsecured)
    }

    @Test
    fun `matching transient stubs should honour setup order and ignore Accept q priority`() {
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/products"),
            headersPattern = HttpHeadersPattern(mapOf("$ACCEPT?" to StringPattern()))
        )

        val jsonStub = HttpStubData(
            requestType = requestPattern,
            response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = "{}"),
            responsePattern = HttpResponsePattern(HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = "{}")),
            resolver = Resolver(),
            scenarioStub = ScenarioStub(request = HttpRequest("GET", "/products"), response = HttpResponse.OK)
        )

        val xmlStub = HttpStubData(
            requestType = requestPattern,
            response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml"), body = "<type>xml</type>"),
            responsePattern = HttpResponsePattern(HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml"), body = "<type>xml</type>")),
            resolver = Resolver(),
            scenarioStub = ScenarioStub(request = HttpRequest("GET", "/products"), response = HttpResponse.OK)
        )

        val expectations = ThreadSafeListOfStubs(mutableListOf(jsonStub, xmlStub), emptyMap())
        val result = expectations.matchingTransientStub(
            HttpRequest("GET", "/products", headers = mapOf(ACCEPT to "application/json;q=1.0, application/xml;q=0.2"))
        )

        assertThat(result?.first).isEqualTo(xmlStub)
    }

    @Test
    fun `matching static stubs should honour setup order and ignore Accept q priority`() {
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/products"),
            headersPattern = HttpHeadersPattern(mapOf("$ACCEPT?" to StringPattern()))
        )

        val jsonStub = HttpStubData(
            requestType = requestPattern,
            response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = "{}"),
            responsePattern = HttpResponsePattern(HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = "{}")),
            resolver = Resolver()
        )

        val xmlStub = HttpStubData(
            requestType = requestPattern,
            response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml"), body = "<type>xml</type>"),
            responsePattern = HttpResponsePattern(HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml"), body = "<type>xml</type>")),
            resolver = Resolver()
        )

        val expectations = ThreadSafeListOfStubs(mutableListOf(jsonStub, xmlStub), emptyMap())
        val result = expectations.matchingStaticStub(
            HttpRequest("GET", "/products", headers = mapOf(ACCEPT to "application/xml;q=1.0, application/json;q=0.2"))
        )

        assertThat(result.first).isEqualTo(jsonStub)
    }

    @Test
    fun `matching dynamic stubs should honour setup order and ignore Accept q priority`() {
        val requestPattern = HttpRequestPattern(
            method = "GET",
            httpPathPattern = buildHttpPathPattern("/products"),
            headersPattern = HttpHeadersPattern(mapOf("$ACCEPT?" to StringPattern()))
        )

        val jsonStub = HttpStubData(
            requestType = requestPattern,
            response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = "{}"),
            responsePattern = HttpResponsePattern(HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"), body = "{}")),
            resolver = Resolver(),
            scenarioStub = ScenarioStub(request = HttpRequest("GET", "/products"), response = HttpResponse.OK)
        )

        val xmlStub = HttpStubData(
            requestType = requestPattern,
            response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml"), body = "<type>xml</type>"),
            responsePattern = HttpResponsePattern(HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/xml"), body = "<type>xml</type>")),
            resolver = Resolver(),
            scenarioStub = ScenarioStub(request = HttpRequest("GET", "/products"), response = HttpResponse.OK)
        )

        val expectations = ThreadSafeListOfStubs(mutableListOf(jsonStub, xmlStub), emptyMap())
        val result = expectations.matchingDynamicStub(
            HttpRequest("GET", "/products", headers = mapOf(ACCEPT to "application/xml;q=1.0, application/json;q=0.2"))
        )

        assertThat(result.first).isEqualTo(jsonStub)
    }

    @Test
    fun `matching static stubs should reject success when accept header does not allow response content type`() {
        val stubData = HttpStubData(
            requestType = HttpRequest("GET", "/products").toPattern(),
            response = HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/json"),
                body = "{}"
            ),
            responsePattern = HttpResponsePattern(
                HttpResponse(
                    status = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{}"
                )
            ),
            resolver = Resolver()
        )

        val expectations = ThreadSafeListOfStubs(mutableListOf(stubData), emptyMap())
        val result = expectations.matchingStaticStub(HttpRequest("GET", "/products", headers = mapOf(ACCEPT to "application/xml")))

        assertThat(result.first).isNull()
        assertThat(result.second.any { it.first is Result.Failure }).isTrue()
    }

    @Nested
    inner class StubAssociatedToTests {

        @Test
        fun `should return a ThreadSafeListOfStubs for a given port`() {
            val specToBaseUrlMap = mapOf(
                "spec1.yaml" to "http://localhost:8080"
            )
            val httpStubs = mutableListOf(
                mockk<HttpStubData> {
                    every { contractPath } returns "spec1.yaml"
                },
                mockk<HttpStubData> {
                    every { contractPath } returns "spec2.yaml"
                }
            )

            val threadSafeList = ThreadSafeListOfStubs(httpStubs, specToBaseUrlMap)

            val result = threadSafeList.stubAssociatedTo(
                baseUrl = "http://localhost:8080",
                defaultBaseUrl = "http://localhost:9090",
                urlPath = ""
            )

            assertNotNull(result)
            assertThat(result?.size).isEqualTo(1)
        }

        @Test
        fun `should return null if port has no associated stubs`() {
            val specToBaseUrlMap = mapOf(
                "spec1.yaml" to "http://localhost:8080",
                "spec2.yaml" to "http://localhost:8080",
                "spec3.yaml" to "http://localhost:8000"
            )
            val httpStubs = mutableListOf(
                mockk<HttpStubData> {
                    every { contractPath } returns "spec1.yaml"
                },
                mockk<HttpStubData> {
                    every { contractPath } returns "spec2.yaml"
                }
            )

            val threadSafeList = ThreadSafeListOfStubs(httpStubs, specToBaseUrlMap)

            val result = threadSafeList.stubAssociatedTo(
                baseUrl = "http://localhost:8000",
                defaultBaseUrl = "http://localhost:9090",
                urlPath = ""
            )

            assertThat(result.size).isEqualTo(0)
        }

        @Test
        fun `should return a ThreadSafeListOfStubs for the default port if port not found in map`() {
            val specToBaseUrlMap = mapOf(
                "spec1.yaml" to "http://localhost:8080"
            )
            val httpStubs = mutableListOf(
                mockk<HttpStubData> {
                    every { contractPath } returns "spec1.yaml"
                },
                mockk<HttpStubData> {
                    every { contractPath } returns "spec2.yaml"
                },
                mockk<HttpStubData> {
                    every { contractPath } returns "spec3.yaml"
                }
            )

            val threadSafeList = ThreadSafeListOfStubs(httpStubs, specToBaseUrlMap)

            val result = threadSafeList.stubAssociatedTo(
                baseUrl = "http://localhost:9090",
                defaultBaseUrl = "http://localhost:9090",
                urlPath = ""
            )

            assertNotNull(result)
            assertEquals(2, result!!.size)
        }

        @Test
        fun `should return multiple stubs associated with the same port`() {
            val specToBaseUrlMap = mapOf(
                "spec1.yaml" to "http://localhost:8080",
                "spec2.yaml" to "http://localhost:8080",
                "spec3.yaml" to "http://localhost:8080"
            )
            val httpStubs = mutableListOf(
                mockk<HttpStubData> {
                    every { contractPath } returns "spec1.yaml"
                },
                mockk<HttpStubData> {
                    every { contractPath } returns "spec2.yaml"
                },
                mockk<HttpStubData> {
                    every { contractPath } returns "spec3.yaml"
                }
            )

            val threadSafeList = ThreadSafeListOfStubs(httpStubs, specToBaseUrlMap)

            val result = threadSafeList.stubAssociatedTo(
                baseUrl = "http://localhost:8080",
                defaultBaseUrl = "http://localhost:9090",
                urlPath = ""
            )

            assertNotNull(result)
            assertEquals(3, result!!.size)
        }

        @Test
        fun `should return an empty list if no stubs exist`() {
            val specToBaseUrlMap = mapOf("spec1.yaml" to "http://localhost:8080")
            val httpStubs = mutableListOf<HttpStubData>()

            val threadSafeList = ThreadSafeListOfStubs(httpStubs, specToBaseUrlMap)

            val result = threadSafeList.stubAssociatedTo(
                baseUrl = "http://localhost:8080",
                defaultBaseUrl = "http://localhost:9090",
                urlPath = ""
            )

            assertThat(result.size).isEqualTo(0)
        }
    }
    @Nested
    inner class ExpectationPrioritization {
        private val specificRequest = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "Specific Value"}"""))
        private val generalRequest = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "(string)"}"""))

        private val specificExpectation = HttpStubData(
            requestType = specificRequest.toPattern(),
            response = HttpResponse.ok(parsedJSONObject("{\"id\": 10}")),
            responsePattern = HttpResponsePattern(HttpResponse.ok(parsedJSONObject("{\"id\": 10}"))),
            resolver = Resolver(),
            originalRequest = specificRequest
        )

        private val generalExpectation = HttpStubData(
            requestType = generalRequest.toPattern(),
            response = HttpResponse.ok(parsedJSONObject("{\"id\": 20}")),
            responsePattern = HttpResponsePattern(HttpResponse.ok(parsedJSONObject("{\"id\": 20}"))),
            resolver = Resolver(),
            originalRequest = generalRequest
        )

        private val sandwichedSpecificExpectation = mutableListOf(generalExpectation, specificExpectation, generalExpectation)
        val expectations = ThreadSafeListOfStubs(sandwichedSpecificExpectation, emptyMap())

        @Test
        fun `it should prioritize specific over general expectations`() {
            val responseToSpecificValue = expectations.matchingStaticStub(specificRequest)
            val expectedResponse = responseToSpecificValue.first ?: fail("Expected a response for the given request to be found")

            val jsonResponse = expectedResponse.response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByName("id")?.toStringLiteral()).isEqualTo("10")
        }

        @Test
        fun `it should use the general expectation when the specific does not match the request`() {
            val responseToSpecificValue = expectations.matchingStaticStub(generalRequest)
            val expectedResponse = responseToSpecificValue.first ?: fail("Expected a response for the given request to be found")

            val jsonResponse = expectedResponse.response.body as JSONObjectValue
            assertThat(jsonResponse.findFirstChildByName("id")?.toStringLiteral()).isEqualTo("20")
        }
    }

    @Nested
    inner class PartialStubPrioritization {
        @Test
        fun `getPartialBySpecificityAndGenerality should select highest specificity`() {
            // Create mock partial matches with different specificity values
            val lowSpecificityRequest = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "(string)"}"""))
            val highSpecificityRequest = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "Laptop"}"""))
            
            val lowSpecificityStub = HttpStubData(
                requestType = lowSpecificityRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                scenarioStub = ScenarioStub(
                    partial =
                        ScenarioStub(
                            request = lowSpecificityRequest,
                            response = HttpResponse.ok(parsedJSONObject("{\"id\": 1}"))
                        )
                )
            )
            
            val highSpecificityStub = HttpStubData(
                requestType = highSpecificityRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                scenarioStub = ScenarioStub(
                    partial =
                        ScenarioStub(
                            request = highSpecificityRequest,
                            response = HttpResponse.ok(parsedJSONObject("{\"id\": 2}"))
                        )
                )
            )
            
            val partials = listOf(
                lowSpecificityStub,
                highSpecificityStub
            )
            
            val result = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(partials)
            
            assertNotNull(result)
            assertEquals(highSpecificityStub, result)
        }
        
        @Test
        fun `getPartialBySpecificityAndGenerality should select lowest generality when specificity is equal`() {
            // Create mock partial matches with same specificity but different generality
            val lowGeneralityRequest = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "Laptop"}"""))
            val highGeneralityRequest = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "(string)"}"""))
            
            val lowGeneralityStub = HttpStubData(
                requestType = lowGeneralityRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                scenarioStub = ScenarioStub(
                    partial =
                        ScenarioStub(request = lowGeneralityRequest, response = HttpResponse.ok(parsedJSONObject("{\"id\": 1}")))
                )
            )
            
            val highGeneralityStub = HttpStubData(
                requestType = highGeneralityRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                scenarioStub = ScenarioStub(
                    partial =
                        ScenarioStub(request = highGeneralityRequest, response = HttpResponse.ok(parsedJSONObject("{\"id\": 2}")))
                )
            )
            
            val partials = listOf(
                highGeneralityStub,
                lowGeneralityStub
            )
            
            val result = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(partials)
            
            assertNotNull(result)
            assertEquals(lowGeneralityStub, result)
        }

        @Test
        fun `getPartialBySpecificityAndGenerality should prioritize literal path over interpolated path`() {
            val interpolatedPathRequest = HttpRequest("GET", "/items/item-(id:string)")
            val literalPathRequest = HttpRequest("GET", "/items/item-123")

            val interpolatedPathStub = HttpStubData(
                requestType = interpolatedPathRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                scenarioStub = ScenarioStub(
                    partial = ScenarioStub(
                        request = interpolatedPathRequest,
                        response = HttpResponse.ok(parsedJSONObject("{\"id\": 1}"))
                    )
                )
            )

            val literalPathStub = HttpStubData(
                requestType = literalPathRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                scenarioStub = ScenarioStub(
                    partial = ScenarioStub(
                        request = literalPathRequest,
                        response = HttpResponse.ok(parsedJSONObject("{\"id\": 2}"))
                    )
                )
            )

            val result = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(
                listOf(interpolatedPathStub, literalPathStub)
            )

            assertNotNull(result)
            assertEquals(literalPathStub, result)
        }

        @Test
        fun `getPartialBySpecificityAndGenerality should retain first unresolved stub when requests are unresolved`() {
            val unresolvedStub1 = mockk<HttpStubData> { every { resolveOriginalRequest() } returns null }
            val unresolvedStub2 = mockk<HttpStubData> { every { resolveOriginalRequest() } returns null }
            val result = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(
                listOf(unresolvedStub1, unresolvedStub2)
            )

            assertEquals(unresolvedStub1, result)
        }
    }

    private fun stubWithAuthoredRequest(
        responseBody: String,
        authoredRequest: HttpRequest,
        securitySchemes: List<OpenAPISecurityScheme> = listOf(
            APIKeyInHeaderSecurityScheme(SECURITY_HEADER, null)
        ),
        requestType: HttpRequestPattern = matchingRequestPattern()
    ): HttpStubData {
        val response = HttpResponse(status = 200, body = responseBody)
        val scenario = Scenario(
            name = responseBody,
            httpRequestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/products"),
                securitySchemes = securitySchemes
            ),
            httpResponsePattern = HttpResponsePattern(response),
            protocol = SpecmaticProtocol.HTTP,
            specType = SpecType.OPENAPI
        )

        return HttpStubData(
            requestType = requestType,
            response = response,
            responsePattern = HttpResponsePattern(response),
            resolver = Resolver(),
            scenario = scenario,
            originalRequest = authoredRequest
        )
    }

    private fun matchingRequestPattern(
        headers: Map<String, Pattern> = mapOf("$SECURITY_HEADER?" to StringPattern())
    ): HttpRequestPattern = HttpRequestPattern(
        method = "GET",
        httpPathPattern = buildHttpPathPattern("/products"),
        headersPattern = HttpHeadersPattern(headers)
    )

    private fun requestWithSecurityHeader(): HttpRequest = HttpRequest(
        method = "GET",
        path = "/products",
        headers = mapOf(SECURITY_HEADER to "matching-token")
    )

    companion object {
        private const val SECURITY_HEADER = "X-Access-Key"
        private const val SECURITY_QUERY_PARAMETER = "access_key"
    }
}
