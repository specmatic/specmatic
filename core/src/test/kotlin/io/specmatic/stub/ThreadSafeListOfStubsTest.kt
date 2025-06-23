package io.specmatic.stub

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ThreadSafeListOfStubsTest {

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
            responsePattern = HttpResponsePattern(HttpResponse.OK),
            resolver = Resolver(),
            originalRequest = specificRequest
        )

        private val generalExpectation = HttpStubData(
            requestType = generalRequest.toPattern(),
            response = HttpResponse.ok(parsedJSONObject("{\"id\": 20}")),
            responsePattern = HttpResponsePattern(HttpResponse.OK),
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
                partial = ScenarioStub(request = lowSpecificityRequest, response = HttpResponse.ok(parsedJSONObject("{\"id\": 1}")))
            )
            
            val highSpecificityStub = HttpStubData(
                requestType = highSpecificityRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                partial = ScenarioStub(request = highSpecificityRequest, response = HttpResponse.ok(parsedJSONObject("{\"id\": 2}")))
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
                partial = ScenarioStub(request = lowGeneralityRequest, response = HttpResponse.ok(parsedJSONObject("{\"id\": 1}")))
            )
            
            val highGeneralityStub = HttpStubData(
                requestType = highGeneralityRequest.toPattern(),
                response = HttpResponse.ok(""),
                responsePattern = HttpResponsePattern(HttpResponse.OK),
                resolver = Resolver(),
                partial = ScenarioStub(request = highGeneralityRequest, response = HttpResponse.ok(parsedJSONObject("{\"id\": 2}")))
            )
            
            val partials = listOf(
                highGeneralityStub,
                lowGeneralityStub
            )
            
            val result = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(partials)
            
            assertNotNull(result)
            assertEquals(lowGeneralityStub, result)
        }
    }

}