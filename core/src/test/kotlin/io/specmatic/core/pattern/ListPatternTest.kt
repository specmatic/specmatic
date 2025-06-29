package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.specmatic.shouldNotMatch
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows

internal class ListPatternTest {
    @Test
    fun `should filter out optional keys from all the elements`() {
        val pattern = ListPattern(parsedPattern("""{
            "topLevelMandatoryKey": "(number)",
            "topLevelOptionalKey?": "(string)",
            "subMandatoryObject": {
                "subMandatoryKey": "(string)",
                "subOptionalKey?": "(number)"
            }
        }
        """.trimIndent()))
        val matchingValue = parsedValue("""
        [
            {
                "topLevelMandatoryKey": 10,
                "topLevelOptionalKey": "value",
                "subMandatoryObject": {
                    "subMandatoryKey": "value",
                    "subOptionalKey": 10
                }
            },
            {
                "topLevelMandatoryKey": 10,
                "topLevelOptionalKey": "value",
                "subMandatoryObject": {
                    "subMandatoryKey": "value",
                    "subOptionalKey": 10
                }
            }
        ]   
        """.trimIndent())

        val valueWithoutOptionals = pattern.eliminateOptionalKey(matchingValue, Resolver())
        val expectedValue = parsedValue("""
        [
            {
                "topLevelMandatoryKey": 10,
                "subMandatoryObject": {
                    "subMandatoryKey": "value"
                }
            },
            {
                "topLevelMandatoryKey": 10,
                "subMandatoryObject": {
                    "subMandatoryKey": "value"
                }
            }
        ]
        """.trimIndent())

        assertThat(valueWithoutOptionals).isEqualTo(expectedValue)
    }

    @Test
    fun `should fail to match nulls gracefully`() {
        NullValue shouldNotMatch ListPattern(StringPattern())
    }

    @Test
    fun `should encompass itself`() {
        val type = ListPattern(NumberPattern())
        assertThat(type.encompasses(type, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `list of nullable type should encompass another list the same non-nullable type`() {
        val bigger = ListPattern(parsedPattern("""(number?)"""))
        val smallerWithNumber = ListPattern(parsedPattern("""(number)"""))
        val smallerWithNull = ListPattern(parsedPattern("""(number)"""))
        assertThat(bigger.encompasses(smallerWithNumber, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(bigger.encompasses(smallerWithNull, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should not encompass another list with different type`() {
        val numberPattern = ListPattern(parsedPattern("""(number?)"""))
        val stringPattern = ListPattern(parsedPattern("""(string)"""))
        assertThat(numberPattern.encompasses(stringPattern, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `a list should encompass a json array with items matching the list`() {
        val bigger = ListPattern(AnyPattern(listOf(NumberPattern(), NullPattern), extensions = emptyMap()))
        val smaller1Element = parsedPattern("""["(number)"]""")
        val smaller1ElementAndRest = parsedPattern("""["(number)", "(number...)"]""")

        assertThat(bigger.encompasses(smaller1Element, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(bigger.encompasses(smaller1ElementAndRest, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should fail if there are any match failures at all`() {
        val bigger = ListPattern(NumberPattern())
        val matching = parsedPattern("""["(number)", "(string...)"]""")

        assertThat(bigger.encompasses(matching, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `list type with recursive type definition should be validated without an infinite loop`() {
        val gherkin = """
Feature: Recursive test

  Scenario: Recursive scenario
    Given type Data
    | id   | (number) |
    | data | (Data*)   |
    When GET /
    Then status 200
    And response-body (Data)
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)
        val result = testBackwardCompatibility(feature, feature)
        println(result.report())
        assertThat(result.success()).isTrue()
    }

    @Nested
    inner class ReturnAllErrors {
        private val listType = ListPattern(NumberPattern())
        val list = parsedJSON("""["elementA", 2, "elementC"]""")
        val result: Result.Failure = listType.matches(list, Resolver()) as Result.Failure
        private val resultText = result.toFailureReport().toText()

        @Test
        fun `should return all errors in a list`() {
            assertThat(result.toMatchFailureDetailList()).hasSize(2)
        }

        @Test
        fun `should refer to all errors in the report`() {
            println(resultText)
            assertThat(resultText).contains("[0]")
            assertThat(resultText).contains("elementA")
            assertThat(resultText).contains("[2]")
            assertThat(resultText).contains("elementC")
        }
    }

    @Tag(GENERATION)
    @Test
    fun `negative pattern generation`() {
        val negativePatterns =
            ListPattern(StringPattern()).negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(negativePatterns.map { it.typeName }).containsExactlyInAnyOrder(
            "list of null",
            "list of number",
            "list of boolean"
        )
    }

    @Tag(GENERATION)
    @Test
    fun `should generate a list of patterns each of which is a list pattern`() {
        val patterns = ListPattern(NumberPattern()).newBasedOn(Row(), Resolver()).map { it.value }

        for(pattern in patterns) {
            assertTrue(pattern is ListPattern)
        }
    }

    @Tag(GENERATION)
    @Test
    fun `should use the inline example for generation of values`() {
        val value = ListPattern(NumberPattern(), example = listOf("1", "2", "3")).generate(Resolver(defaultExampleResolver = UseDefaultExample))
        assertThat(value).isEqualTo(JSONArrayValue(listOf(NumberValue(1), NumberValue(2), NumberValue(3))))
    }

    @Nested
    inner class AllPatternsMandatory {
        @Test
        fun `should result in failure when list is empty`() {
            val pattern = ListPattern(parsedPattern("""{
            "topLevelMandatoryKey": "(number)",
            "topLevelOptionalKey?": "(string)",
            "subMandatoryObject": {
                "subMandatoryKey": "(string)",
                "subOptionalKey?": "(number)"
            }
        }
        """.trimIndent()))

            val matchingValue = parsedValue(LIST_BREAD_CRUMB.trimIndent())
            val result = pattern.matches(matchingValue, Resolver().withAllPatternsAsMandatory())
            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("List cannot be empty")
        }

        @Test
        fun `should not result in failure when list is empty but pattern is cycling`() {
            val basePattern = ListPattern(parsedPattern("""{
            "topLevelMandatoryKey": "(number)",
            "topLevelOptionalKey?": "(string)",
            "subList": "(baseListPattern)"
        }
        """.trimIndent(), typeAlias = "(baseJsonPattern)"), typeAlias = "(baseListPattern)")
            val listPattern = ListPattern(basePattern)

            val value = parsedValue("""[
            [
                {
                    "topLevelMandatoryKey": 10,
                    "topLevelOptionalKey": "abc",
                    "subList": []
                },
                {
                    "topLevelMandatoryKey": 10,
                    "topLevelOptionalKey": "abc",
                    "subList": []
                }
            ]
        ]
        """.trimIndent()) as JSONArrayValue
            val result = listPattern.matches(value, Resolver(newPatterns = mapOf("(baseListPattern)" to basePattern)).withAllPatternsAsMandatory())

            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat(result.reportString()).isEmpty()
        }

        @Test
        fun `should not result in failure for missing keys when pattern is cycling with an allOf schema`() {
            val spec = """
        openapi: 3.0.0
        info:
          title: Sample API
          description: Sample API
          version: 0.1.9
        paths:
          /hello:
            get:
              responses:
                '200':
                  description: Says hello
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/MainMessage'
        components:
          schemas:
            MainMessage:
              allOf:
                - ${"$"}ref: '#/components/schemas/Message'
            Message:
              type: object
              properties:
                message:
                  type: string
                details:
                  type: array
                  items:
                    ${"$"}ref: '#/components/schemas/Details'
            Details:
              oneOf:
                - ${"$"}ref: '#/components/schemas/Message'
        """.trimIndent()
            val feature = OpenApiSpecification.fromYAML(spec, "").toFeature()

            val scenario = feature.scenarios.first()
            val resolver = scenario.resolver.copy(allPatternsAreMandatory = true)

            val responsePattern = scenario.httpResponsePattern.body
            val value = responsePattern.generate(resolver)
            println(value.toStringLiteral())

            val matchResult = responsePattern.matches(value, resolver)
            println(matchResult.reportString())

            assertThat(matchResult).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should complain when optional keys are missing and the pattern is an AnyPattern`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub1")),
                        "details?" to DeferredPattern("(List)")
                    ), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub2")),
                        "details?" to DeferredPattern("(List)")
                    ), typeAlias = "(Sub2)"),
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )
            val listPattern = ListPattern(DeferredPattern("(Base)"), typeAlias = "(List)")

            val resolver = Resolver(
                newPatterns = mapOf("(List)" to listPattern, "(Base)" to pattern)
            ).withAllPatternsAsMandatory()

            val value = JSONArrayValue(listOf(
                JSONObjectValue(mapOf(
                    "type" to StringValue("sub1")
                )),
                JSONObjectValue(mapOf(
                    "type" to StringValue("sub2")
                ))
            ))
            val result = listPattern.matches(value, resolver)

            println(result.reportString())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
            >> [0].details
            Expected optional key named "details" was missing
            >> [1].details
            Expected optional key named "details" was missing
            """.trimIndent())
        }

        @Test
        fun `should not complain for a generated value when the pattern is an AnyPattern`() {
            val pattern = AnyPattern(
                listOf(
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub1")),
                        "details?" to DeferredPattern("(List)")
                    ), typeAlias = "(Sub1)"),
                    JSONObjectPattern(mapOf(
                        "type" to ExactValuePattern(StringValue("sub2")),
                        "details?" to DeferredPattern("(List)")
                    ), typeAlias = "(Sub2)"),
                ), typeAlias = "(Base)",
                discriminator = Discriminator(
                    property = "type",
                    values = setOf("sub1", "sub2"),
                    mapping = mapOf("sub1" to "#/components/schemas/Sub1", "sub2" to "#/components/schemas/Sub2")
                )
            )
            val listPattern = ListPattern(DeferredPattern("(Base)"), typeAlias = "(List)")

            val resolver = Resolver(
                newPatterns = mapOf("(List)" to listPattern, "(Base)" to pattern)
            ).withAllPatternsAsMandatory()

            val value = listPattern.generate(resolver)
            val result = listPattern.matches(value, resolver)

            println(result.reportString())
            assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class FixValueTests {
        @Test
        fun `should be able to fix simple invalid values in an json array`() {
            val innerPattern = parsedPattern("""
            {
                "topLevelKey": "(string)",
                "topLevelOptionalKey?": "(number)",
                "nested": {
                    "nestedKey": "(date)",
                    "nestedOptionalKey?": "(boolean)"
                }
            }
            """.trimIndent(), typeAlias = "(Test)")
            val pattern = ListPattern(innerPattern)
            val patternDictionary = """
            Test:
                topLevelKey: Fixed
                nested:
                    nestedOptionalKey: true
            """.let(Dictionary::fromYaml)

            val invalidValue = parsedValue("""[
                {
                    "topLevelKey": 999,
                    "topLevelOptionalKey": 10,
                    "nested": {
                        "nestedKey": "2025-01-01",
                        "nestedOptionalKey": "false"
                    }
                }
            ]
            """.trimIndent())
            val fixedValue = pattern.fixValue(invalidValue, Resolver(dictionary = patternDictionary)) as JSONArrayValue
            println(fixedValue.toStringLiteral())

            assertThat(fixedValue.list).allSatisfy {
                it as JSONObjectValue
                assertThat(it).isEqualTo(parsedJSONObject("""
                {
                    "topLevelKey": "Fixed",
                    "nested": {
                        "nestedKey": "2025-01-01",
                        "nestedOptionalKey": true
                    },
                    "topLevelOptionalKey": 10
                }
                """.trimIndent()))
            }
        }

        @Test
        fun `should generate if the value does not match the type expected json-array`() {
            val innerPattern = parsedPattern("""
            {
                "topLevelKey": "(string)",
                "topLevelOptionalKey?": "(number)",
                "nested": {
                    "nestedKey": "(date)",
                    "nestedOptionalKey?": "(boolean)"
                }
            }
            """.trimIndent(), typeAlias = "(Test)")
            val pattern = ListPattern(innerPattern)
            val patternDictionary = """
            Test:
                topLevelKey: Fixed
                nested:
                    nestedKey: 2025-01-01
            """.let(Dictionary::fromYaml)

            val invalidValue = JSONObjectValue()
            val fixedValue = pattern.fixValue(invalidValue, Resolver(dictionary = patternDictionary))
            println(fixedValue.toStringLiteral())

            assertThat((fixedValue as JSONArrayValue).list).allSatisfy {
                assertThat(it).isEqualTo(parsedJSONObject("""
                {
                    "topLevelKey": "Fixed",
                    "nested": {
                        "nestedKey": "2025-01-01"
                    }
                }
                """.trimIndent()))
            }
        }

        @Test
        fun `should not generate when the pattern is avoidably cycling and value is missing`() {
            val pattern = ListPattern(parsedPattern("""{
                "topLevelKey": "(string)",
                "topLevelOptionalKey?": "(number)",
                "subList?": "(TestList)"
            }
            """.trimIndent(), typeAlias = "(Test)"), typeAlias = "(TestList)")
            val patternDictionary = """
            Test:
                topLevelKey: Fixed
                topLevelOptionalKey: 999
            """.let(Dictionary::fromYaml)

            val value = parsedValue("""
            [
                {
                    "topLevelKey": 999,
                    "topLevelOptionalKey": "Invalid"
                }
            ]
            """.trimIndent())
            val fixedValue = pattern.fixValue(value, Resolver(newPatterns = mapOf("(TestList)" to pattern), dictionary = patternDictionary))
            println(fixedValue.toStringLiteral())

            assertThat(fixedValue.toStringLiteral()).isEqualTo("""
            [
                {
                    "topLevelKey": "Fixed",
                    "topLevelOptionalKey": 999
                }
            ]
            """.trimIndent())
        }

        @Test
        fun `should throw an exception when the pattern is unavoidably cycling and value is missing`() {
            val pattern = ListPattern(parsedPattern("""{
                "topLevelKey": "(string)",
                "topLevelOptionalKey?": "(number)",
                "subList": "(TestList)"
            }
            """.trimIndent(), typeAlias = "(Test)"), typeAlias = "(TestList)")
            val patternDictionary = """
            Test:
                topLevelKey: Fixed
                topLevelOptionalKey: 999
            """.let(Dictionary::fromYaml)

            val value = parsedValue("""
            [
                {
                    "topLevelKey": 999,
                    "topLevelOptionalKey": "Invalid"
                }
            ]
            """.trimIndent())
            val exception = assertThrows<ContractException> { pattern.fixValue(value, Resolver(newPatterns = mapOf("(TestList)" to pattern), dictionary = patternDictionary)) }

            println(exception.report())
            assertThat(exception.failure().reportString()).isEqualToNormalizingWhitespace("""
            >> subList[0 (random)].subList[0 (random)].topLevelKey
            Invalid pattern cycle: Test, Test, Test
            """.trimIndent())
        }

        @Test
        fun `should generate new values if the list is empty and allPatternsAreMandatory is set`() {
            val innerPattern = parsedPattern("""
            {
                "topLevelKey": "(string)",
                "topLevelOptionalKey?": "(number)"
            }
            """.trimIndent(), typeAlias = "(Test)")
            val pattern = ListPattern(innerPattern)
            val patternDictionary = """
            Test:
                topLevelKey: Fixed
                topLevelOptionalKey: 10
            """.let(Dictionary::fromYaml)

            val emptyList = parsedValue(LIST_BREAD_CRUMB)
            val fixedValue = pattern.fixValue(emptyList, Resolver(dictionary = patternDictionary).withAllPatternsAsMandatory())
            println(fixedValue.toStringLiteral())

            assertThat((fixedValue as JSONArrayValue).list).isNotEmpty
            assertThat(fixedValue.list).allSatisfy {
                assertThat(it).isEqualTo(
                    parsedValue(
                        """
                        {
                            "topLevelKey": "Fixed",
                            "topLevelOptionalKey": 10 
                        }
                        """.trimIndent()
                    )
                )
            }
        }

        @Test
        fun `should not generate when the pattern is avoidably cycling and value is missing even if allPatternsAreMandatory is set`() {
            val pattern = ListPattern(parsedPattern("""{
                "topLevelKey": "(string)",
                "topLevelOptionalKey?": "(number)",
                "subList?": "(TestList)"
            }
            """.trimIndent(), typeAlias = "(Test)"), typeAlias = "(TestList)")
            val patternDictionary = """
            Test:
                topLevelKey: Fixed
                topLevelOptionalKey: 999
            """.let(Dictionary::fromYaml)

            val value = parsedValue("""
            [
                {
                    "topLevelKey": 999,
                    "topLevelOptionalKey": "Invalid"
                }
            ]
            """.trimIndent())
            val fixedValue = pattern.fixValue(
                value = value,
                resolver = Resolver(newPatterns = mapOf("(TestList)" to pattern), dictionary = patternDictionary).withAllPatternsAsMandatory()
            ) as JSONArrayValue
            println(fixedValue.toStringLiteral())

            assertThat(fixedValue.list).allSatisfy {
                it as JSONObjectValue
                assertThat(it.getString("topLevelKey")).isEqualTo("Fixed")
                assertThat(it.getInt("topLevelOptionalKey")).isEqualTo(999)
                assertThat(it.getJSONArray("subList")).allSatisfy { nested ->
                    nested as JSONObjectValue
                    assertThat(nested).isEqualTo(parsedJSONObject("""
                     {
                        "topLevelKey": "Fixed",
                        "topLevelOptionalKey": 999
                    }
                    """.trimIndent()))
                }
            }
        }

        @Test
        fun `should retain pattern token if it matches when resolver is in mock mode`() {
            val innerPattern = JSONObjectPattern(mapOf("number" to NumberPattern(), "string" to StringPattern()), typeAlias = "(Object)")
            val pattern = ListPattern(innerPattern, typeAlias = "(List)")
            val resolver = Resolver(newPatterns = mapOf("(List)" to pattern, "(Object)" to innerPattern), mockMode = true)
            val validValues = listOf(
                StringValue("(List)"),
                JSONArrayValue(listOf(StringValue("(Object)"))),
            )

            assertThat(validValues).allSatisfy {
                val fixedValue = pattern.fixValue(it, resolver)
                println(fixedValue.toStringLiteral())
                assertThat(fixedValue).isEqualTo(it)
            }
        }

        @Test
        fun `should generate value when pattern token does not match when resolver is in mock mode`() {
            val innerPattern = JSONObjectPattern(mapOf("number" to NumberPattern(), "string" to StringPattern()), typeAlias = "(Object)")
            val pattern = ListPattern(innerPattern, typeAlias = "(List)")
            val resolver = Resolver(newPatterns = mapOf("(List)" to pattern, "(Object)" to innerPattern), mockMode = true)
            val invalidValues = listOf(
                StringValue("(string)"),
                JSONArrayValue(listOf(StringValue("(string)"))),
            )

            assertThat(invalidValues).allSatisfy {
                val fixedValue = pattern.fixValue(it, resolver)
                println(fixedValue.toStringLiteral())
                assertThat(fixedValue).isNotEqualTo(it).isInstanceOf(JSONArrayValue::class.java)
                assertThat((fixedValue as JSONArrayValue).list).hasOnlyElementsOfType(JSONObjectValue::class.java)
            }
        }

        @Test
        fun `should generate values even if pattern token matches but resolver is not in mock mode`() {
            val innerPattern = JSONObjectPattern(mapOf("number" to NumberPattern(), "string" to StringPattern()), typeAlias = "(Object)")
            val pattern = ListPattern(innerPattern, typeAlias = "(List)")
            val resolver = Resolver(newPatterns = mapOf("(List)" to pattern, "(Object)" to innerPattern), mockMode = false)
            val validValues = listOf(
                StringValue("(List)"),
                JSONArrayValue(listOf(StringValue("(Object)"))),
            )

            assertThat(validValues).allSatisfy {
                val fixedValue = pattern.fixValue(it, resolver)
                println(fixedValue.toStringLiteral())
                assertThat(fixedValue).isNotEqualTo(it).isInstanceOf(JSONArrayValue::class.java)
                assertThat((fixedValue as JSONArrayValue).list).hasOnlyElementsOfType(JSONObjectValue::class.java)
            }
        }

        @Test
        fun `should not generate values when list is empty and resolver is partial`() {
            val innerPattern = JSONObjectPattern(mapOf("number" to NumberPattern()), typeAlias = "(Object)")
            val pattern = ListPattern(innerPattern, typeAlias = "(List)")
            val resolver = Resolver(newPatterns = mapOf("(List)" to pattern, "(Object)" to innerPattern)).partializeKeyCheck()
            val partialValue = JSONArrayValue(listOf())

            val fixedValue = pattern.fixValue(partialValue, resolver)
            assertThat(fixedValue).isEqualTo(partialValue)
        }
    }

    @Nested
    inner class FillInTheBlanksTests {

        @Test
        fun `should fill in missing mandatory elements using dictionary`() {
            val listPattern = ListPattern(StringPattern())
            val jsonArray = JSONArrayValue(listOf(StringValue("(string)")))
            val resolver = Resolver(dictionary = "(string): Value".let(Dictionary::fromYaml))

            val filledJsonArray = listPattern.fillInTheBlanks(jsonArray, resolver).value as JSONArrayValue
            assertThat(filledJsonArray.list).isEqualTo(listOf(StringValue("Value")))
        }

        @Test
        fun `should complain if pattern token does not match the underlying pattern`() {
            val listPattern = ListPattern(NumberPattern())
            val jsonArray = JSONArrayValue(listOf(StringValue("(string)")))
            val resolver = Resolver()

            val result = listPattern.fillInTheBlanks(jsonArray, resolver)
            assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
            assertThat(result.failure.reportString()).isEqualToNormalizingWhitespace("""
            >> [0]
            Expected number, actual was string
            """.trimIndent()
            )
        }

        @Test
        fun `should handle any-value pattern token as a special case`() {
            val listPattern = ListPattern(StringPattern())
            val jsonArray = JSONArrayValue(listOf(StringValue("(anyvalue)")))
            val dictionary = "(string): Value".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary)

            val filledJsonArray = listPattern.fillInTheBlanks(jsonArray, resolver).value as JSONArrayValue
            assertThat(filledJsonArray.list).isEqualTo(listOf(StringValue("Value")))
        }

        @Test
        fun `should generate a new value if supplied value is pattern token`() {
            val listPattern = ListPattern(NumberPattern(), typeAlias = "(Test)")
            val jsonArray = StringValue("(Test)")

            val dictionary = "(number): 999".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary, newPatterns = mapOf("(Test)" to listPattern))
            val filledJsonArray = listPattern.fillInTheBlanks(jsonArray, resolver).value as JSONArrayValue

            assertThat(filledJsonArray.list).allSatisfy {
                assertThat(it).isEqualTo(NumberValue(999))
            }
        }

        @Test
        fun `should result in failure when pattern token does not match pattern itself`() {
            val listPattern = ListPattern(StringPattern())
            val invalidPatterns = listOf(
                JSONObjectPattern(mapOf("key" to StringPattern())),
                ListPattern(BooleanPattern()),
                NumberPattern()
            )

            assertThat(invalidPatterns).allSatisfy {
                val resolver = Resolver(newPatterns = mapOf("(Test)" to it))
                val value = StringValue("(Test)")
                val result = listPattern.fillInTheBlanks(value, resolver)

                assertThat(result).isInstanceOf(HasFailure::class.java); result as HasFailure
                assertThat(result.failure.reportString()).satisfiesAnyOf(
                    { report -> assertThat(report).containsIgnoringWhitespaces("Expected array or list type") },
                    { report -> assertThat(report).containsIgnoringWhitespaces("Expected string, actual was boolean") },
                )
            }
        }

        @Test
        fun `should not generate if list is empty`() {
            val listPattern = ListPattern(StringPattern())
            val jsonArray = JSONArrayValue(emptyList())
            val resolver = Resolver()

            val filledJsonArray = listPattern.fillInTheBlanks(jsonArray, resolver).value as JSONArrayValue
            assertThat(filledJsonArray.list).isEmpty()
        }

        @Test
        fun `should generate if list is empty when allPatternsMandatory is set`() {
            val listPattern = ListPattern(StringPattern())
            val jsonArray = JSONArrayValue(emptyList())
            val resolver = Resolver(dictionary = "(string): Value".let(Dictionary::fromYaml)).withAllPatternsAsMandatory()

            val filledJsonArray = listPattern.fillInTheBlanks(jsonArray, resolver).value as JSONArrayValue
            assertThat(filledJsonArray.list).allSatisfy {
                assertThat(it).isEqualTo(StringValue("Value"))
            }
        }

        @Test
        fun `should return value as is if not json-array or empty when resolver is negative`() {
            val listPattern = ListPattern(StringPattern())
            val resolver = Resolver(isNegative = true)
            val values = listOf(
                NullValue,
                StringValue("Value"),
                JSONArrayValue(emptyList())
            )

            assertThat(values).allSatisfy {
                val result = listPattern.fillInTheBlanks(it, resolver)
                assertThat(result).isEqualTo(HasValue(it))
            }
        }

        @Test
        fun `should generate if value is patternToken even when resolver is negative`() {
            val listPattern = ListPattern(NumberPattern(), typeAlias = "(Test)")
            val jsonArray = StringValue("(Test)")

            val dictionary = "(number): 999".let(Dictionary::fromYaml)
            val resolver = Resolver(dictionary = dictionary, newPatterns = mapOf("(Test)" to listPattern), isNegative = true)
            val filledJsonArray = listPattern.fillInTheBlanks(jsonArray, resolver).value as JSONArrayValue

            assertThat(filledJsonArray.list).allSatisfy {
                assertThat(it).isEqualTo(NumberValue(999))
            }
        }

        @Test
        fun `should allow invalid pattern tokens when resolver is negative`() {
            val listPattern = ListPattern(StringPattern())
            val invalidPatterns = listOf(
                JSONObjectPattern(mapOf("key" to StringPattern())),
                ListPattern(BooleanPattern()),
                NumberPattern()
            )

            assertThat(invalidPatterns).allSatisfy {
                val resolver = Resolver(newPatterns = mapOf("(Test)" to it), isNegative = true)
                val value = StringValue("(Test)")
                val result = listPattern.fillInTheBlanks(value, resolver)

                assertThat(result).isInstanceOf(HasValue::class.java); result as HasValue
                println(result.value)
            }
        }
    }

    @Nested
    inner class CalculatePathTests {
        @Test
        fun `calculatePath should return empty set for non-JSONArrayValue input`() {
            val pattern = ListPattern(StringPattern())
            val value = StringValue("not an array")
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should return empty set for empty array`() {
            val pattern = ListPattern(AnyPattern(listOf(StringPattern()), extensions = emptyMap()))
            val value = JSONArrayValue(emptyList())
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should handle AnyPattern elements`() {
            val pattern = ListPattern(AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap()))
            val value = JSONArrayValue(listOf(StringValue("test"), NumberValue(42), StringValue("test2")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder("{[0]}{string}", "{[1]}{number}", "{[2]}{string}")
        }

        @Test
        fun `calculatePath should handle JSONObjectPattern elements`() {
            val objectPattern = JSONObjectPattern(
                mapOf("data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())),
                typeAlias = "(ListItem)"
            )
            val pattern = ListPattern(objectPattern)
            val value = JSONArrayValue(listOf(
                JSONObjectValue(mapOf("data" to StringValue("item1"))),
                JSONObjectValue(mapOf("data" to StringValue("item2")))
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder(
                "{[0]}{ListItem}.data{string}",
                "{[1]}{ListItem}.data{string}"
            )
        }

        @Test
        fun `calculatePath should handle nested AnyPatterns in objects`() {
            val nestedObjectPattern = JSONObjectPattern(
                mapOf(
                    "id" to StringPattern(),
                    "value" to AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap())
                ),
                typeAlias = "(NestedListItem)"
            )
            val pattern = ListPattern(nestedObjectPattern)
            val value = JSONArrayValue(listOf(
                JSONObjectValue(mapOf("id" to StringValue("1"), "value" to StringValue("text"))),
                JSONObjectValue(mapOf("id" to StringValue("2"), "value" to NumberValue(123)))
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactlyInAnyOrder(
                "{[0]}{NestedListItem}.value{string}",
                "{[1]}{NestedListItem}.value{number}"
            )
        }

        @Test
        fun `calculatePath should handle patterns without typeAlias`() {
            val objectPattern =
                JSONObjectPattern(mapOf("data" to AnyPattern(listOf(StringPattern()), extensions = emptyMap())))
            val pattern = ListPattern(objectPattern)
            val value = JSONArrayValue(listOf(
                JSONObjectValue(mapOf("data" to StringValue("item1")))
            ))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{[0]}.data{string}")
        }

        @Test
        fun `calculatePath should handle scalar patterns without AnyPattern`() {
            val pattern = ListPattern(StringPattern())
            val value = JSONArrayValue(listOf(StringValue("item1"), StringValue("item2")))
            val resolver = Resolver()

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).isEmpty()
        }

        @Test
        fun `calculatePath should handle DeferredPattern in AnyPattern`() {
            val pattern = ListPattern(
                AnyPattern(listOf(DeferredPattern("(TestType)")), extensions = emptyMap())
            )
            val resolver = Resolver(newPatterns = mapOf("(TestType)" to StringPattern()))
            val value = JSONArrayValue(listOf(StringValue("test")))

            val paths = pattern.calculatePath(value, resolver)

            assertThat(paths).containsExactly("{[0]}{TestType}")
        }

        @Test
        fun `calculatePath should handle large arrays efficiently`() {
            val pattern = ListPattern(AnyPattern(listOf(StringPattern()), extensions = emptyMap()))
            val largeArray = JSONArrayValue((1..100).map { StringValue("item$it") })
            val resolver = Resolver()

            val paths = pattern.calculatePath(largeArray, resolver)

            assertThat(paths).hasSize(100)
            assertThat(paths).allMatch { it.matches(Regex("\\{\\[\\d+]\\}\\{string\\}")) }
        }
    }
}
