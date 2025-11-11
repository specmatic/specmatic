package io.specmatic.core.jsonoperator

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestResponseOperatorTest {
    private val mockScenario = mockk<Scenario> {
        every { httpRequestPattern } returns mockk {
            every { httpPathPattern } returns null
        }
        every { resolver } returns mockk()
    }

    @Test
    fun `should route to request operator for request paths`() {
        val request = HttpRequest(method = "GET", path = "/api/users", body = StringValue("request body"))
        val response = HttpResponse(status = 200, body = StringValue("response body"))
        val operator = RequestResponseOperator.from(request, response, mockScenario)

        val result = operator.get("/request/body").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("request body"))
    }

    @Test
    fun `should route to response operator for response paths`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val response = HttpResponse(status = 200, body = StringValue("response body"))
        val operator = RequestResponseOperator.from(request, response, mockScenario)

        val result = operator.get("/response/body").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("response body"))
    }

    @Test
    fun `should access nested paths through routing`() {
        val request = HttpRequest(
            method = "GET",
            path = "/api/users",
            headers = mapOf("Authorization" to "Bearer token"),
        )

        val response = HttpResponse(status = 404)
        val operator = RequestResponseOperator.from(request, response, mockScenario)

        val requestHeader = operator.get("/request/header/Authorization").finalizeValue()
        assertThat(requestHeader.value.getOrNull()).isEqualTo(StringValue("Bearer token"))

        val responseStatus = operator.get("/response/statusCode").finalizeValue()
        assertThat(responseStatus.value.getOrNull()).isEqualTo(NumberValue(404))
    }

    @Test
    fun `should fail when routing to invalid key`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val response = HttpResponse(status = 200)
        val operator = RequestResponseOperator.from(request, response, mockScenario)

        val result = operator.get("/invalid/body")
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString())
            .contains("must be one of request, response")
    }

    @Test
    fun `should finalize to JSONObjectValue with request and response`() {
        val request = HttpRequest(method = "POST", path = "/api/users", body = StringValue("test"))
        val response = HttpResponse(status = 201, body = StringValue("created"))
        val operator = RequestResponseOperator.from(request, response, mockScenario)

        val result = operator.finalize().value
        assertThat(result).isInstanceOf(JSONObjectValue::class.java)

        val jsonObject = (result as JSONObjectValue).jsonObject
        assertThat(jsonObject).containsKeys("request", "response")
    }
}
