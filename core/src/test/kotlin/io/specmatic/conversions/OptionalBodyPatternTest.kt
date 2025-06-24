package io.specmatic.conversions

import io.specmatic.conversions.OptionalBodyPattern
import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptionalBodyPatternTest {
    @Test
    fun `optional body error match`() {
        val body = OptionalBodyPattern.fromPattern(NumberPattern())

        val matchResult = body.matches(StringValue("abc"), Resolver())
        assertThat(matchResult.reportString().trim()).isEqualTo("Expected number, actual was \"abc\"")
    }

    @Test
    fun `resolveSubstitutions should return value when no substitution needed`() {
        val bodyPattern = NumberPattern()
        val pattern = OptionalBodyPattern.fromPattern(bodyPattern)
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
        val numericValue = NumberValue(123)

        val result = pattern.resolveSubstitutions(substitution, numericValue, resolver, "body")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(numericValue)
    }

    @Test
    fun `resolveSubstitutions should handle variable substitution`() {
        val bodyPattern = StringPattern()
        val pattern = OptionalBodyPattern.fromPattern(bodyPattern)
        val originalRequest = HttpRequest("GET", "/", mapOf(), StringValue("test body"))
        val runningRequest = HttpRequest("GET", "/", mapOf(), StringValue("test body"))
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
        val stringValue = StringValue("test body")

        val result = pattern.resolveSubstitutions(substitution, stringValue, resolver, "body")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(stringValue)
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value doesn't match body pattern`() {
        val bodyPattern = NumberPattern()
        val pattern = OptionalBodyPattern.fromPattern(bodyPattern)
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
        val invalidValue = StringValue("not_a_number")

        val result = pattern.resolveSubstitutions(substitution, invalidValue, resolver, "body")

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }
}