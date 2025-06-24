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
    fun `resolveSubstitutions should handle data lookup substitution with valid value`() {
        val bodyPattern = StringPattern()
        val pattern = OptionalBodyPattern.fromPattern(bodyPattern)
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "message" to StringValue("Welcome to Engineering")
                    )),
                    "sales" to JSONObjectValue(mapOf(
                        "message" to StringValue("Welcome to Sales")
                    ))
                ))
            ))
        ))
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].message)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, "body")

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(StringValue("Welcome to Engineering"))
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value doesn't match body pattern`() {
        val bodyPattern = NumberPattern()
        val pattern = OptionalBodyPattern.fromPattern(bodyPattern)
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "count" to StringValue("not_a_number")
                    ))
                ))
            ))
        ))
        val substitution = Substitution(
            runningRequest,
            originalRequest,
            HttpPathPattern(emptyList(), ""),
            HttpHeadersPattern(mapOf()),
            JSONObjectPattern(mapOf("department" to StringPattern())),
            resolver,
            dataLookup
        )
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].count)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, "body")

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }
}