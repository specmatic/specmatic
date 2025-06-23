package io.specmatic.core.pattern

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.value.*
import io.specmatic.mock.ScenarioStub
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch
import io.specmatic.stub.HttpStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class JSONArrayPatternTest {
    @Test
    fun `An array with a number should match an array pattern with a number pattern`() {
        val value = parsedValue("[1]")
        val pattern = parsedPattern("""["(number)"]""")

        value shouldMatch pattern
    }

    @Test
    fun `An array with a string should not match an array pattern with a number pattern`() {
        val value = parsedValue("""["abc"]""")
        val pattern = parsedPattern("""["(number)"]""")

        value shouldNotMatch pattern
    }

    @Test
    fun `An empty array should match an empty array pattern`() {
        val value = parsedValue("[]")
        val pattern = parsedPattern("""[]""")

        value shouldMatch pattern
    }

    @Test
    fun `An empty array should not match an populated array pattern`() {
        val value = parsedValue(LIST_BREAD_CRUMB)
        val pattern = parsedPattern("""["(number)"]""")

        value shouldNotMatch pattern
    }

    @Test
    fun `A populated array should not match an empty array pattern`() {
        val value = parsedValue("[1]")
        val pattern = parsedPattern("""[]""")

        value shouldNotMatch pattern
    }

    @Test
    fun `An empty array should match an array pattern`() {
        val value = parsedValue(LIST_BREAD_CRUMB)
        val pattern = parsedPattern("""["(number*)"]""")

        value shouldMatch pattern
    }

    @Test
    fun `An numerical array value match a numerical array pattern`() {
        val value = parsedValue("""[1, 2]""")
        val pattern = parsedPattern("""["(number*)"]""")

        value shouldMatch  pattern
    }

    @Test
    fun `An string array value should not match a numerical array pattern`() {
        val value = parsedValue("""["one", "two"]""")
        val pattern = parsedPattern("""["(number*)"]""")

        value shouldNotMatch  pattern
    }



    @Test
    fun `JSON array of numbers should match stubbed json array containing number type`() {
        val value = parsedValue("""["(number)"]""")
        val pattern = JSONArrayPattern(listOf(NumberPattern()))

        val resolver = Resolver(mockMode = true)

        assertThat(resolver.matchesPattern(null, pattern, value)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `An array with the first n elements should not match an array with all the elements`() {
        val value = parsedValue("[1,2]")
        val pattern = parsedPattern("""[1,2,3]""")

        Assertions.assertFalse(pattern.matches(value, Resolver()).isSuccess())

    }

    @Test
    fun `should fail to match nulls gracefully`() {
        NullValue shouldNotMatch JSONArrayPattern(listOf(StringPattern(), StringPattern()))
    }

    @Test
    fun `should encompass itself`() {
        val type = parsedPattern("""["(number)", "(number)"]""")
        assertThat(type.encompasses(type, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not encompass a pattern with a different number of items`() {
        val bigger = parsedPattern("""["(number)", "(number)"]""")
        val smallerLess = parsedPattern("""["(number)"]""")
        val smallerMore = parsedPattern("""["(number)"]""")

        assertThat(bigger.encompasses(smallerLess, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        assertThat(bigger.encompasses(smallerMore, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `finite array should not encompass a list`() {
        val smaller = parsedPattern("""["(number)", "(number)"]""")
        val bigger = ListPattern(NumberPattern())

        assertThat(smaller.encompasses(bigger, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `should encompass a list containing a subtype of all elements`() {
        val bigger = parsedPattern("""["(number)", "(number...)"]""")
        val alsoBigger = parsedPattern("""["(number...)"]""")
        val matching = ListPattern(NumberPattern())

        assertThat(bigger.encompasses(matching, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(alsoBigger.encompasses(matching, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `finite array should not encompass an infinite array`() {
        val bigger = parsedPattern("""["(number)", "(number...)"]""")
        val smaller = parsedPattern("""["(number)", "(number)", "(number)"]""")

        assertThat(smaller.encompasses(bigger, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `smaller infinite array should match larger infinite array if all types match`() {
        val bigger = parsedPattern("""["(number)", "(number...)"]""")
        val matching = parsedPattern("""["(number)", "(number)", "(number...)"]""")

        assertThat(bigger.encompasses(matching, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `bigger infinite array should match smaller infinite array if all types match`() {
        val bigger = parsedPattern("""["(number)", "(number)", "(number...)"]""")
        val matching = parsedPattern("""["(number)", "(number...)"]""")

        assertThat(bigger.encompasses(matching, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should fail if there are any match failures at all`() {
        val bigger = parsedPattern("""["(number)", "(number...)"]""")
        val matching = parsedPattern("""["(number)", "(string...)"]""")

        assertThat(bigger.encompasses(matching, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `json array type with recursive type definition should be validated without an infinite loop`() {
        val gherkin = """
Feature: Recursive test

  Scenario: Recursive scenario
    Given type Data ["(number)", "(Data)"]
    When GET /
    Then status 200
    And response-body (Data)
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)
        val result = testBackwardCompatibility(feature, feature)
        println(result.report())
        assertThat(result.success()).isTrue()
    }

    @Test
    fun `json array of objects with recursive type definition should be validated without an infinite loop`() {
        val gherkin = """
Feature: Recursive test

  Scenario: Recursive scenario
    Given type Data ["(number)", "(MoreData)"]
    And type MoreData
    | data | (Data) |
    When GET /
    Then status 200
    And response-body (Data)
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)
        val result = testBackwardCompatibility(feature, feature)
        println(result.report())
        assertThat(result.success()).isTrue()
    }

    @Test
    fun `json array of object containing list with recursive type definition should be validated without an infinite loop`() {
        val gherkin = """
Feature: Recursive test

  Scenario: Recursive scenario
    Given type Data ["(number)", "(MoreData)"]
    And type MoreData
    | data | (Data*) |
    When GET /
    Then status 200
    And response-body (Data)
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)
        val result = testBackwardCompatibility(feature, feature)
        println(result.report())
        assertThat(result.success()).isTrue()
    }

    @Test
    fun `json array pattern matching value with pattern minus 1 elements elements should throw ContractException`() {
        val type = JSONArrayPattern(listOf(StringPattern(), StringPattern()))
        val result = type.matches(JSONArrayValue(listOf(StringValue("test"))), Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `json array pattern matching value with pattern minus 2 elements elements should throw ContractException`() {
        val type = JSONArrayPattern(listOf(StringPattern(), StringPattern(), StringPattern()))
        val result = type.matches(JSONArrayValue(listOf(StringValue("test"))), Resolver())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `array values are differentiators`() {
        val spec = """
openapi: 3.0.0
info:
  title: Order API
  version: 1.0.0
paths:
  /orders:
    post:
      summary: Create a new order
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: array
              items:
                type: object
                required:
                  - name
                properties:
                  name:
                    type: string
      responses:
        '200':
          description: Order created successfully
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        HttpStub(feature).use { stub ->

            val expectations = listOf(
                ScenarioStub(
                    HttpRequest("POST", "/orders", body = parsedJSONArray("""[]""")),
                    HttpResponse.ok("empty order")
                ),

                ScenarioStub(
                    HttpRequest("POST", "/orders", body = parsedJSONArray("""[{"name": "Notebook"}]""")),
                    HttpResponse.ok("has one order")
                ),

                ScenarioStub(
                    HttpRequest(
                        "POST",
                        "/orders",
                        body = parsedJSONArray("""[{"name": "Notebook"}, {"name": "Pencil"}]""")
                    ),
                    HttpResponse.ok("has one order")
                )
            )

            expectations.forEach { expectation ->
                stub.client.execute(
                    HttpRequest("POST", "/_specmatic/expectations", body = expectation.toJSON())
                ).let { response ->
                    assertThat(response.status).isEqualTo(200)
                }
            }

            expectations.forEach { expectation ->
                stub.client.execute(expectation.request).let { response ->
                    assertThat(response.body.toStringLiteral()).isEqualTo(expectation.response.body.toStringLiteral())
                }
            }

        }
    }

    @Test
    fun `array with incorrect contained value is not accepted`() {
        val spec = """
openapi: 3.0.0
info:
  title: Order API
  version: 1.0.0
paths:
  /orders:
    post:
      summary: Create a new order
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: array
              items:
                type: object
                required:
                  - name
                properties:
                  name:
                    type: string
      responses:
        '200':
          description: Order created successfully
          content:
            application/json:
              schema:
                type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

        HttpStub(feature).use { stub ->

            val invalidStub = ScenarioStub(
                HttpRequest("POST", "/orders", body = parsedJSONArray("""[{"name": 10}]""")),
                HttpResponse.ok("empty order")
            )

            stub.client.execute(
                HttpRequest("POST", "/_specmatic/expectations", body = invalidStub.toJSON())
            ).let { response ->
                assertThat(response.status).isEqualTo(400)
            }

        }
    }

    @Nested
    inner class CalculatePathTests {
        @Test
        fun `calculatePath should return empty set for non-JSONArrayValue input`() {
            val pattern = JSONArrayPattern(listOf(StringPattern()))
            val value = StringValue("not an array")
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should return empty set for empty array`() {
            val pattern = JSONArrayPattern(listOf(AnyPattern(listOf(StringPattern()), extensions = emptyMap())))
            val value = JSONArrayValue(emptyList())
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should handle single pattern with AnyPattern`() {
            val pattern = JSONArrayPattern(listOf(AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())))
            val value = JSONArrayValue(listOf(StringValue("test"), NumberValue(42)))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{number}")
        }

        @Test
        fun `calculatePath should handle single pattern with JSONObjectPattern`() {
            val objectPattern = JSONObjectPattern(
                mapOf("data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                typeAlias = "(Item)"
            )
            val pattern = JSONArrayPattern(listOf(objectPattern))
            val value = JSONArrayValue(listOf(
                JSONObjectValue(mapOf("data" to StringValue("item1"))),
                JSONObjectValue(mapOf("data" to StringValue("item2")))
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder(
                "{[0]}{Item}.data{string}",
                "{[1]}{Item}.data{string}"
            )
        }

        @Test
        fun `calculatePath should handle multiple patterns with different types`() {
            val pattern = JSONArrayPattern(listOf(
                AnyPattern(listOf(StringPattern()), extensions = emptyMap()),
                AnyPattern(listOf(NumberPattern()), extensions = emptyMap()),
                AnyPattern(listOf(BooleanPattern()), extensions = emptyMap())
            ))
            val value = JSONArrayValue(listOf(
                StringValue("test"),
                NumberValue(42),
                BooleanValue(true)
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{number}", "{[2]}{boolean}")
        }

        @Test
        fun `calculatePath should handle array with more elements than patterns`() {
            val pattern = JSONArrayPattern(listOf(AnyPattern(listOf(StringPattern()), extensions = emptyMap())))
            val value = JSONArrayValue(listOf(
                StringValue("item1"),
                StringValue("item2"),
                StringValue("item3")
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{string}", "{[2]}{string}")
        }

        @Test
        fun `calculatePath should handle multiple patterns with some elements missing`() {
            val pattern = JSONArrayPattern(listOf(
                AnyPattern(listOf(StringPattern()), extensions = emptyMap()),
                AnyPattern(listOf(NumberPattern()), extensions = emptyMap()),
                AnyPattern(listOf(BooleanPattern()), extensions = emptyMap())
            ))
            val value = JSONArrayValue(listOf(
                StringValue("test"),
                NumberValue(42)
                // Missing third element
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{number}")
        }

        @Test
        fun `calculatePath should handle nested JSONObjectPattern in array`() {
            val nestedObjectPattern = JSONObjectPattern(
                mapOf(
                    "id" to StringPattern(),
                    "value" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                ),
                typeAlias = "(NestedItem)"
            )
            val pattern = JSONArrayPattern(listOf(nestedObjectPattern))
            val value = JSONArrayValue(listOf(
                JSONObjectValue(mapOf("id" to StringValue("1"), "value" to StringValue("text"))),
                JSONObjectValue(mapOf("id" to StringValue("2"), "value" to NumberValue(123)))
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder(
                "{[0]}{NestedItem}.value{string}",
                "{[1]}{NestedItem}.value{number}"
            )
        }

        @Test
        fun `calculatePath should wrap scalar types in braces`() {
            val pattern =
                JSONArrayPattern(listOf(AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())))
            val value = JSONArrayValue(listOf(StringValue("test")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{[0]}{string}")
        }

        @Test
        fun `calculatePath should handle array with no matching patterns`() {
            val pattern = JSONArrayPattern(listOf(NumberPattern()))
            val value = JSONArrayValue(listOf(StringValue("test")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }
    }
}
