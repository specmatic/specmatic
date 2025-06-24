package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnyNonNullJSONValueTest {
    @Test
    fun `should match non-null values`() {
        val pattern = AnyNonNullJSONValue()
        val resolver = Resolver()

        val validValues = listOf(
            StringValue("test"),
            NumberValue(42),
            BooleanValue(true),
            JSONObjectValue(mapOf("key" to StringValue("value"))),
            JSONArrayValue(listOf(StringValue("item")))
        )

        validValues.forEach { value ->
            val result = pattern.matches(value, resolver)
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }
    }

    @Test
    fun `should not match null values`() {
        val pattern = AnyNonNullJSONValue()
        val resolver = Resolver()

        val result = pattern.matches(NullValue, resolver)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `resolveSubstitutions should return value when no substitution needed`() {
        val pattern = AnyNonNullJSONValue()
        val resolver = Resolver()
        val substitution = Substitution(
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val validValue = StringValue("test")

        val result = pattern.resolveSubstitutions(substitution, validValue, resolver, "field")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(validValue)
    }

    @Test
    fun `resolveSubstitutions should handle variable substitution for non-null values`() {
        val pattern = AnyNonNullJSONValue()
        val originalRequest = HttpRequest("GET", "/", mapOf("data" to "test_value"), EmptyString)
        val runningRequest = HttpRequest("GET", "/", mapOf("data" to "test_value"), EmptyString)
        val resolver = Resolver()
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val nonNullValue = NumberValue(100)

        val result = pattern.resolveSubstitutions(substitution, nonNullValue, resolver, "data")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(nonNullValue)
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value is null`() {
        val pattern = AnyNonNullJSONValue()
        val resolver = Resolver()
        val substitution = Substitution(
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val nullValue = NullValue

        val result = pattern.resolveSubstitutions(substitution, nullValue, resolver, "field")

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should handle complex JSON objects during substitution`() {
        val pattern = AnyNonNullJSONValue()
        val resolver = Resolver()
        val substitution = Substitution(
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val complexValue = JSONObjectValue(mapOf(
            "name" to StringValue("John"),
            "age" to NumberValue(30),
            "active" to BooleanValue(true)
        ))

        val result = pattern.resolveSubstitutions(substitution, complexValue, resolver, "user")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(complexValue)
    }

    @Test
    fun `should handle arrays during substitution`() {
        val pattern = AnyNonNullJSONValue()
        val resolver = Resolver()
        val substitution = Substitution(
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpRequest("GET", "/", mapOf(), EmptyString),
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            EmptyStringPattern,
            resolver,
            JSONObjectValue(mapOf())
        )
        val arrayValue = JSONArrayValue(listOf(
            StringValue("item1"),
            StringValue("item2"),
            NumberValue(42)
        ))

        val result = pattern.resolveSubstitutions(substitution, arrayValue, resolver, "items")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(arrayValue)
    }
}