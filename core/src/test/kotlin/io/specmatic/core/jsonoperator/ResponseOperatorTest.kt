package io.specmatic.core.jsonoperator

import io.specmatic.core.HttpResponse
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseOperatorTest {
    @Test
    fun `should be able to retrieve body using pointer`() {
        val response = HttpResponse(status = 200, body = StringValue("test body"))
        val operator = ResponseOperator.from(response)
        val result = operator.get("/body").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("test body"))
    }

    @Test
    fun `should be able to retrieve status code using pointer`() {
        val response = HttpResponse(status = 201)
        val operator = ResponseOperator.from(response)
        val result = operator.get("/statusCode").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(NumberValue(201))
    }

    @Test
    fun `should be able to retrieve headers using pointer`() {
        val response = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json", "X-Custom-Header" to "custom-value"),
        )
        val operator = ResponseOperator.from(response)

        val contentTypeResult = operator.get("/header/Content-Type").finalizeValue()
        assertThat(contentTypeResult.value.getOrNull()).isEqualTo(StringValue("application/json"))

        val customHeaderResult = operator.get("/header/X-Custom-Header").finalizeValue()
        assertThat(customHeaderResult.value.getOrNull()).isEqualTo(StringValue("custom-value"))
    }

    @Test
    fun `should be able to update body using pointer`() {
        val response = HttpResponse(status = 200, body = StringValue("old body"))
        val operator = ResponseOperator.from(response)
        val updatedOperator = operator.update("/body", StringValue("new body"))
        val result = updatedOperator.value.get("/body").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("new body"))
    }

    @Test
    fun `should be able to update header using pointer`() {
        val response = HttpResponse(status = 200, headers = mapOf("Authorization" to "Bearer old-token"))
        val operator = ResponseOperator.from(response)
        val updatedOperator = operator.update("/header/Authorization", StringValue("Bearer new-token"))
        val result = updatedOperator.value.get("/header/Authorization").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("Bearer new-token"))
    }

    @Test
    fun `should be able to insert new header using pointer`() {
        val response = HttpResponse(status = 200, headers = mapOf("Content-Type" to "application/json"))
        val operator = ResponseOperator.from(response)
        val updatedOperator = operator.insert("/header/X-Request-Id", StringValue("request-123"))
        val result = updatedOperator.value.get("/header/X-Request-Id").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("request-123"))
    }

    @Test
    fun `should be able to delete header using pointer`() {
        val response = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json", "X-Custom" to "value"),
        )
        val operator = ResponseOperator.from(response)
        val updatedOperator = operator.delete("/header/X-Custom")
        val result = updatedOperator.value.getOrThrow().get("/header/X-Custom")
        assertThat(result.value).isInstanceOf(Optional.None::class.java)
    }

    @Test
    fun `should fail when trying to update with invalid route key`() {
        val response = HttpResponse(status = 200)
        val operator = ResponseOperator.from(response)
        val result = operator.update("/invalid", StringValue("value"))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString())
            .contains("Invalid key invalid, must be oneof header, statusCode, body")
    }

    @Test
    fun `should fail when trying to update header with non-ObjectValueOperator`() {
        val response = HttpResponse(status = 200)
        val operator = ResponseOperator.from(response)

        val result = operator.copyWithRoute("header", ValueOperator(StringValue("test")))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("header must stay object")
    }

    @Test
    fun `should allow body route to accept any RootMutableJsonOperator`() {
        val response = HttpResponse(status = 200, body = StringValue("test"))
        val operator = ResponseOperator.from(response)

        val newBodyOperator = ObjectValueOperator(mapOf("key" to StringValue("value")))
        val result = operator.copyWithRoute("body", newBodyOperator)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value.finalize().value.body).isEqualTo(newBodyOperator.finalize().value)
    }

    @Test
    fun `should finalize response with updated values`() {
        val response = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "text/plain"),
            body = StringValue("original body"),
        )
        val operator = ResponseOperator.from(response)

        val updatedOperator = operator
            .update("/body", StringValue("updated body")).value
            .update("/header/Content-Type", StringValue("application/json")).value

        val finalizedResponse = updatedOperator.finalize().value

        assertThat(finalizedResponse.status).isEqualTo(200)
        assertThat(finalizedResponse.body).isEqualTo(StringValue("updated body"))
        assertThat(finalizedResponse.headers["Content-Type"]).isEqualTo("application/json")
    }

    @Test
    fun `should handle empty headers`() {
        val response = HttpResponse(status = 200)
        val operator = ResponseOperator.from(response)
        val result = operator.get("/header")
        assertThat(result).isInstanceOf(HasValue::class.java)
    }

    @Test
    fun `should retrieve routes map with all expected keys`() {
        val response = HttpResponse(status = 200)
        val operator = ResponseOperator.from(response)
        assertThat(operator.routes.keys).containsExactlyInAnyOrder("body", "header", "statusCode")
    }

    @Test
    fun `should preserve status code during operations`() {
        val response = HttpResponse(status = 404, body = StringValue("Not Found"))
        val operator = ResponseOperator.from(response)

        val updatedOperator = operator.update("/body", StringValue("Resource Not Found")).value
        val finalizedResponse = updatedOperator.finalize().value

        assertThat(finalizedResponse.status).isEqualTo(404)
        assertThat(finalizedResponse.body).isEqualTo(StringValue("Resource Not Found"))
    }

    @Test
    fun `should not allow status code to be updated`() {
        val response = HttpResponse(status = 200)
        val operator = ResponseOperator.from(response)
        val result = operator.update("/statusCode", NumberValue(404))
        assertThat(result).isInstanceOf(HasFailure::class.java)
    }
}
