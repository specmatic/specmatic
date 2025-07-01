package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.HttpResponsePattern
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class HttpExpectationsTest {
    private val request = HttpRequest("POST", "/products", body = parsedJSONObject("""{"name": "Specific Value"}"""))

    private val staticStubData = HttpStubData(
        requestType = request.toPattern(),
        response = HttpResponse.ok(parsedJSONObject("{\"id\": 10}")),
        responsePattern = HttpResponsePattern(HttpResponse.ok(parsedJSONObject("{\"id\": 10}"))),
        resolver = Resolver(),
        contractPath = "test.yaml",
        originalRequest = request
    )

    private val sandwichedSpecificExpectation = mutableListOf(staticStubData)
    private val expectations = HttpExpectations(
        sandwichedSpecificExpectation,
        specToBaseUrlMap = mapOf("test.yaml" to "http://localhost:8080")
    )

    @Test
    fun `it should return a matching expectation`() {
        val responseToSpecificValue = expectations.matchingStub(request)
        val expectedResponse = responseToSpecificValue.first ?: fail("Expected a response for the given request to be found")

        val jsonResponse = expectedResponse.response.body as JSONObjectValue
        assertThat(jsonResponse.findFirstChildByName("id")?.toStringLiteral()).isEqualTo("10")
    }

    @Test
    fun `it should return a dynamic expectation over a static one`() {
        val response = HttpResponse.ok(parsedJSONObject("{\"id\": 20}"))
        val dynamicStubData = HttpStubData(
            requestType = request.toPattern(),
            response = response,
            responsePattern = HttpResponsePattern(response),
            resolver = Resolver(),
            originalRequest = request
        )

        val scenarioStub = ScenarioStub(
            request = request,
            response = response
        )

        expectations.addDynamic(io.specmatic.core.Result.Success() to dynamicStubData, scenarioStub)

        val responseToSpecificValue = expectations.matchingStub(request)
        val expectedResponse = responseToSpecificValue.first ?: fail("Expected a response for the given request to be found")

        val jsonResponse = expectedResponse.response.body as JSONObjectValue
        assertThat(jsonResponse.findFirstChildByName("id")?.toStringLiteral()).isEqualTo("20")
    }

    @Test
    fun `it should return a transient expectation over a dynamic or static one`() {
        val dynamicStubResponse = HttpResponse.ok(parsedJSONObject("{\"id\": 20}"))
        val dynamicStubData = HttpStubData(
            requestType = request.toPattern(),
            response = dynamicStubResponse,
            responsePattern = HttpResponsePattern(dynamicStubResponse),
            resolver = Resolver(),
            originalRequest = request
        )

        val dynamicScenarioStub = ScenarioStub(
            request = request,
            response = dynamicStubResponse
        )

        expectations.addDynamic(io.specmatic.core.Result.Success() to dynamicStubData, dynamicScenarioStub)

        val transientStubResponse = HttpResponse.ok(parsedJSONObject("{\"id\": 20}"))
        val transientStubData = HttpStubData(
            requestType = request.toPattern(),
            response = transientStubResponse,
            responsePattern = HttpResponsePattern(transientStubResponse),
            resolver = Resolver(),
            originalRequest = request
        )

        val transientScenarioStub = ScenarioStub(
            request = request,
            response = transientStubResponse
        )

        expectations.addDynamic(io.specmatic.core.Result.Success() to transientStubData, transientScenarioStub)

        val responseToSpecificValue = expectations.matchingStub(request)
        val expectedResponse = responseToSpecificValue.first ?: fail("Expected a response for the given request to be found")

        val jsonResponse = expectedResponse.response.body as JSONObjectValue
        assertThat(jsonResponse.findFirstChildByName("id")?.toStringLiteral()).isEqualTo("20")
    }
}