package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.parseGherkinStringToFeature
import io.specmatic.core.Resolver
import io.specmatic.core.testBackwardCompatibility
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.shouldMatch
import io.specmatic.shouldNotMatch

internal class DictionaryPatternTest {
    @Test
    fun `should match a json object with specified pattern`() {
        val pattern = DictionaryPattern(DeferredPattern("(number)"), DeferredPattern("(number)"))
        val value = parsedJSON("""{"1": 1, "2": 2}""")

        value shouldMatch pattern
    }

    @Test
    fun `should not match a json object with non-matching keys`() {
        val pattern = DictionaryPattern(DeferredPattern("(number)"), DeferredPattern("(number)"))
        val value = parsedJSON("""{"not a number": 1, "2": 2}""")

        value shouldNotMatch pattern
    }

    @Test
    fun `should not match a json object with non-matching values`() {
        val pattern = DictionaryPattern(DeferredPattern("(number)"), DeferredPattern("(number)"))
        val value = parsedJSON("""{"1": 1, "2": "two"}""")

        value shouldNotMatch pattern
    }

    @Test
    fun `should match a json object with a key type of number`() {
        val pattern = DictionaryPattern(DeferredPattern("(number)"), DeferredPattern("(string)"))
        parsedValue("""{"1": "1", "2": "two"}""") shouldMatch pattern
        parsedValue("""{"one": "1", "2": "two"}""") shouldNotMatch pattern
    }

    @Test
    fun `should load a matching json object from examples`() {
        val dictionaryType = DictionaryPattern(DeferredPattern("(number)"), DeferredPattern("(string)"))
        val jsonType = toTabularPattern(mapOf("data" to dictionaryType))

        val example = Row(mapOf("data" to """{"1": "one"}"""))
        val newJsonTypes = jsonType.newBasedOn(example, Resolver()).map { it.value }.toList()

        assertThat(newJsonTypes).hasSize(1)

        val exactJson = newJsonTypes.single() as TabularPattern
        assertThat(exactJson).isEqualTo(
            toTabularPattern(
                mapOf(
                    "data" to ExactValuePattern(
                        JSONObjectValue(
                            mapOf(
                                "1" to StringValue(
                                    "one"
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `dictionary containing recursive type definition should be validated without an infinite loop`() {
        val gherkin = """
Feature: Recursive test

  Scenario: Recursive scenario
    Given type Data (dictionary number Data)
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
    fun `dictionary containing recursive type definition with list should be validated without an infinite loop`() {
        val gherkin = """
Feature: Recursive test

  Scenario: Recursive scenario
    Given type Data (dictionary number More)
    And type More
    | id   | (number) |
    | more | (Data*)  |
    When GET /
    Then status 200
    And response-body (Data)
""".trim()

        val feature = parseGherkinStringToFeature(gherkin)
        val result = testBackwardCompatibility(feature, feature)

        println(result.report())

        assertThat(result.success()).isTrue()
    }
}