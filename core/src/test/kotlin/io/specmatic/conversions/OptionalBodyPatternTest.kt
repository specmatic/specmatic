package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.toViolationReportString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OptionalBodyPatternTest {
    @Test
    fun `fixValue should retain NoBodyValue instead of converting to body pattern`() {
        val pattern = OptionalBodyPattern.fromPattern(StringPattern())
        val fixedValue = pattern.fixValue(NoBodyValue, Resolver())
        assertThat(fixedValue).isEqualTo(NoBodyValue)
    }

    @Test
    fun `fixValue should prefer body pattern when value is not no body value`() {
        val pattern = OptionalBodyPattern.fromPattern(
            JSONObjectPattern(
                mapOf(
                    "one" to NumberPattern(),
                    "two" to NumberPattern(),
                    "three" to NumberPattern()
                )
            )
        )

        val value = JSONObjectValue(
            mapOf(
                "one" to NumberValue(1),
                "two" to StringValue("bad"),
                "three" to StringValue("also_bad")
            )
        )

        val fixedValue = pattern.fixValue(value, Resolver())
        assertThat(fixedValue).isInstanceOf(JSONObjectValue::class.java); fixedValue as JSONObjectValue
        assertThat(fixedValue.jsonObject.keys).containsExactlyInAnyOrder("one", "two", "three")
    }

    @Test
    fun `optional body error match`() {
        val body = OptionalBodyPattern.fromPattern(NumberPattern())

        val matchResult = body.matches(StringValue("abc"), Resolver())
        assertThat(matchResult.reportString().trim()).isEqualToNormalizingWhitespace(
            toViolationReportString(
                breadCrumb = null,
                details = DefaultMismatchMessages.typeMismatch("number", "\"abc\"", "string"),
                StandardRuleViolation.TYPE_MISMATCH
            )
        )
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

        val result = pattern.resolveSubstitutions(substitution, numericValue, resolver, null)

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

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

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

        val result = pattern.resolveSubstitutions(substitution, valueExpression, resolver, null)

        assertThat(result).isInstanceOf(HasFailure::class.java)
    }

    @Test
    fun `should generate a positive test with empty body when requestBody is marked optional`() {
        val requests = generatedRequests(
            """
                openapi: 3.0.1
                info:
                  title: Person API
                  version: 1.0.0
                paths:
                  /person:
                    post:
                      requestBody:
                        required: false
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - id
                              properties:
                                id:
                                  type: string
                      responses:
                        200:
                          description: Success
            """.trimIndent()
        )

        assertThat(requests.map { it.body }).anySatisfy {
            assertThat(it).isEqualTo(NoBodyValue)
        }
    }

    @Test
    fun `should generate a positive test with empty body when requestBody required is omitted`() {
        val requests = generatedRequests(
            """
                openapi: 3.0.1
                info:
                  title: Person API
                  version: 1.0.0
                paths:
                  /person:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - id
                              properties:
                                id:
                                  type: string
                      responses:
                        200:
                          description: Success
            """.trimIndent()
        )

        assertThat(requests.map { it.body }).anySatisfy {
            assertThat(it).isEqualTo(NoBodyValue)
        }
    }

    @Test
    fun `should generate both body and no body positive tests when requestBody is optional`() {
        val requests = generatedRequests(
            """
                openapi: 3.0.1
                info:
                  title: Person API
                  version: 1.0.0
                paths:
                  /person:
                    post:
                      requestBody:
                        required: false
                        content:
                          application/json:
                            schema:
                              type: object
                              required:
                                - id
                              properties:
                                id:
                                  type: string
                      responses:
                        200:
                          description: Success
            """.trimIndent()
        )

        assertThat(requests.map { it.body }).anySatisfy {
            assertThat(it).isInstanceOf(JSONObjectValue::class.java)
        }
        assertThat(requests.map { it.body }).anySatisfy {
            assertThat(it).isEqualTo(NoBodyValue)
        }
    }

    private fun generatedRequests(openApiSpec: String): List<HttpRequest> {
        val feature = OpenApiSpecification.fromYAML(openApiSpec, "").toFeature()

        return feature.generateContractTestScenarios(emptyList()).toList().map { it.second.value.generateHttpRequest() }
    }
}
