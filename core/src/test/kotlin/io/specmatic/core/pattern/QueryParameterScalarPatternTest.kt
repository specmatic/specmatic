package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryParameterScalarPatternTest {
    @Test
    fun `should be able to fix invalid values`() {
        val pattern = HttpQueryParamPattern(mapOf("email" to QueryParameterScalarPattern(EmailPattern())))
        val dictionary = "PARAMETERS: { QUERY: { email: SomeDude@example.com } }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val invalidValues = listOf(
            "Unknown",
            "999"
        )

        assertThat(invalidValues).allSatisfy {
            val fixedValue = pattern.fixValue(QueryParameters(mapOf("email" to it)), resolver)
            assertThat(fixedValue.asMap()["email"]).isEqualTo("SomeDude@example.com")
        }
    }

    @Test
    fun `resolveSubstitutions should return value when no substitution needed`() {
        val innerPattern = NumberPattern()
        val pattern = QueryParameterScalarPattern(innerPattern)
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
        val numericValue = NumberValue(42)

        val result = pattern.resolveSubstitutions(substitution, numericValue, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(numericValue)
    }

    @Test
    fun `resolveSubstitutions should handle data lookup substitution with valid value`() {
        val innerPattern = StringPattern()
        val pattern = QueryParameterScalarPattern(innerPattern)
        val originalRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("(DEPARTMENT:string)"))))
        val runningRequest = HttpRequest("POST", "/person", body = JSONObjectValue(mapOf("department" to StringValue("engineering"))))
        val resolver = Resolver()
        val dataLookup = JSONObjectValue(mapOf(
            "dataLookup" to JSONObjectValue(mapOf(
                "dept" to JSONObjectValue(mapOf(
                    "engineering" to JSONObjectValue(mapOf(
                        "project" to StringValue("web-app")
                    )),
                    "sales" to JSONObjectValue(mapOf(
                        "project" to StringValue("crm")
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
        val valueExpression = StringValue("$(dataLookup.dept[DEPARTMENT].project)")

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasValue::class.java)
        assertThat((result as HasValue).value).isEqualTo(StringValue("web-app"))
    }

    @Test
    fun `resolveSubstitutions should fail when substituted value doesn't match inner pattern`() {
        val innerPattern = NumberPattern()
        val pattern = QueryParameterScalarPattern(innerPattern)
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

        val result = pattern.resolveSubstitutions(substitution, invalidValue, resolver, null)

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

}