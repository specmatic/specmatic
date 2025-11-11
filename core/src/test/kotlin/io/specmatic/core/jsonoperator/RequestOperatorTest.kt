package io.specmatic.core.jsonoperator

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.QueryParameters
import io.specmatic.core.Resolver
import io.specmatic.core.URLPathSegmentPattern
import io.specmatic.core.jsonoperator.RootMutableJsonOperator.Companion.finalizeValue
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.jsonoperator.value.ValueOperator
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestOperatorTest {
    private val mockResolver = mockk<Resolver>()
    private val mockRequestPattern = mockk<HttpRequestPattern> {
        every { httpPathPattern } returns HttpPathPattern.from("/")
    }

    @Test
    fun `should be able to retrieve body using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users", body = StringValue("test body"))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val result = operator.get("/body").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("test body"))
    }

    @Test
    fun `should be able to retrieve url using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users/123")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val result = operator.get("/url").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("/api/users/123"))
    }

    @Test
    fun `should extract path parameters when pattern is provided`() {
        val mockPathPattern = mockk<HttpPathPattern>()
        val mockSegmentPattern = mockk<URLPathSegmentPattern>()

        every { mockRequestPattern.httpPathPattern } returns mockPathPattern
        every { mockPathPattern.pathSegmentPatterns } returns listOf(mockSegmentPattern)
        every { mockSegmentPattern.pattern } returns mockk<StringPattern>()
        every { mockSegmentPattern.key } returns "userId"
        every { mockSegmentPattern.parse(any(), any()) } returns StringValue("123")

        val request = HttpRequest(method = "GET", path = "/api/users/123")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)

        val result = operator.get("/path/userId").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("123"))
    }

    @Test
    fun `should be able to retrieve query parameters using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users", queryParams = QueryParameters(mapOf("name" to "John", "age" to "30")))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val nameResult = operator.get("/query/name").finalizeValue()
        assertThat(nameResult.value.getOrNull()).isEqualTo(StringValue("John"))

        val ageResult = operator.get("/query/age").finalizeValue()
        assertThat(ageResult.value.getOrNull()?.toStringValue()).isEqualTo(StringValue("30"))
    }

    @Test
    fun `should be able to retrieve headers using pointer`() {
        val request = HttpRequest(
            method = "GET",
            path = "/api/users",
            headers = mapOf("Authorization" to "Bearer token", "Content-Type" to "application/json"),
        )
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val authResult = operator.get("/header/Authorization").finalizeValue()
        assertThat(authResult.value.getOrNull()).isEqualTo(StringValue("Bearer token"))

        val contentTypeResult = operator.get("/header/Content-Type").finalizeValue()
        assertThat(contentTypeResult.value.getOrNull()).isEqualTo(StringValue("application/json"))
    }

    @Test
    fun `should be able to update body using pointer`() {
        val request = HttpRequest(method = "POST", path = "/api/users", body = StringValue("old body"))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.update("/body", StringValue("new body"))
        val result = updatedOperator.value.get("/body").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("new body"))
    }

    @Test
    fun `should be able to update query parameter using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users", queryParams = QueryParameters(mapOf("name" to "John")))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.update("/query/name", StringValue("Jane"))
        val result = updatedOperator.value.get("/query/name").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("Jane"))
    }

    @Test
    fun `should be able to update header using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users", headers = mapOf("Authorization" to "Bearer old-token"))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.update("/header/Authorization", StringValue("Bearer new-token"))
        val result = updatedOperator.value.get("/header/Authorization").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("Bearer new-token"))
    }

    @Test
    fun `should be able to insert new query parameter using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users", queryParams = QueryParameters(mapOf("name" to "John")))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.insert("/query/age", StringValue("30"))
        val result = updatedOperator.value.get("/query/age").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("30"))
    }

    @Test
    fun `should be able to insert new header using pointer`() {
        val request = HttpRequest(method = "GET", path = "/api/users", headers = mapOf("Authorization" to "Bearer token"))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.insert("/header/X-Custom-Header", StringValue("custom-value"))
        val result = updatedOperator.value.get("/header/X-Custom-Header").finalizeValue()
        assertThat(result.value.getOrNull()).isEqualTo(StringValue("custom-value"))
    }

    @Test
    fun `should be able to delete query parameter using pointer`() {
        val request = HttpRequest(
            method = "GET",
            path = "/api/users",
            queryParams = QueryParameters(mapOf("name" to "John", "age" to "30")),
        )
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.delete("/query/age")
        val result = updatedOperator.value.getOrThrow().get("/query/age")
        assertThat(result.value).isInstanceOf(Optional.None::class.java)
    }

    @Test
    fun `should be able to delete header using pointer`() {
        val request = HttpRequest(
            method = "GET",
            path = "/api/users",
            headers = mapOf("Authorization" to "Bearer token", "X-Custom" to "value"),
        )
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator.delete("/header/X-Custom")
        val result = updatedOperator.value.getOrThrow().get("/header/X-Custom")
        assertThat(result.value).isInstanceOf(Optional.None::class.java)
    }

    @Test
    fun `should fail when trying to update with invalid route key`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val result = operator.update("/invalid", StringValue("value"))

        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString())
            .contains("Invalid key invalid, must be oneof body, path, query, header, url")
    }

    @Test
    fun `should fail when trying to update path with non-ObjectValueOperator`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)

        // Manually construct a scenario where path would be replaced with a ValueOperator
        val pathOperator = ObjectValueOperator(mapOf("id" to StringValue("123")))
        val newOperator = operator.copy(pathOperator = pathOperator)

        // Try to update the entire path route with a ValueOperator
        val result = newOperator.copyWithRoute("path", ValueOperator(StringValue("test")))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("path must stay object")
    }

    @Test
    fun `should fail when trying to update query with non-ObjectValueOperator`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)

        val result = operator.copyWithRoute("query", ValueOperator(StringValue("test")))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("query must stay object")
    }

    @Test
    fun `should fail when trying to update header with non-ObjectValueOperator`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)

        val result = operator.copyWithRoute("header", ValueOperator(StringValue("test")))
        assertThat(result).isInstanceOf(HasFailure::class.java)
        assertThat((result as HasFailure).failure.reportString()).contains("header must stay object")
    }

    @Test
    fun `should finalize request with updated values`() {
        val request = HttpRequest(
            method = "POST",
            path = "/api/users",
            queryParams = QueryParameters(mapOf("filter" to "active")),
            headers = mapOf("Authorization" to "Bearer token"),
            body = StringValue("original body"),
        )

        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val updatedOperator = operator
            .update("/body", StringValue("updated body")).value
            .update("/query/filter", StringValue("inactive")).value
            .update("/header/Authorization", StringValue("Bearer new-token")).value

        val finalizedRequest = updatedOperator.finalize().value
        assertThat(finalizedRequest.body).isEqualTo(StringValue("updated body"))
        assertThat(finalizedRequest.queryParams.paramPairs.first { it.first == "filter" }.second).isEqualTo("inactive")
        assertThat(finalizedRequest.headers["Authorization"]).isEqualTo("Bearer new-token")
    }

    @Test
    fun `should handle empty query parameters`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val result = operator.get("/query")
        assertThat(result).isInstanceOf(HasValue::class.java)
    }

    @Test
    fun `should handle empty headers`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        val result = operator.get("/header")
        assertThat(result).isInstanceOf(HasValue::class.java)
    }

    @Test
    fun `should have routes map with all expected keys`() {
        val request = HttpRequest(method = "GET", path = "/api/users")
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)
        assertThat(operator.routes.keys).containsExactlyInAnyOrder("body", "path", "query", "header", "url")
    }

    @Test
    fun `should allow body route to accept any RootMutableJsonOperator`() {
        val request = HttpRequest(method = "POST", path = "/api/users", body = StringValue("test"))
        val operator = RequestOperator.from(request, mockRequestPattern, mockResolver)

        val newBodyOperator = ObjectValueOperator(mapOf("key" to StringValue("value")))
        val result = operator.copyWithRoute("body", newBodyOperator)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value.finalize().value.body).isEqualTo(newBodyOperator.finalize().value)
    }
}
