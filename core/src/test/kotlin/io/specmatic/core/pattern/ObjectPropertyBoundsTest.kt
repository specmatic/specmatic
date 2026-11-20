package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class ObjectPropertyBoundsTest {
    @ParameterizedTest
    @CsvSource(
        "min | 1 | {} | at least 1 property | with 0 properties",
        "min | 2 | {\"a\":\"x\"} | at least 2 properties | with 1 property",
        "max | 0 | {\"a\":\"x\"} | at most 0 properties | with 1 property",
        "max | 1 | {\"a\":\"x\",\"b\":\"y\"} | at most 1 property | with 2 properties",
        "max | 2 | {\"a\":\"x\",\"b\":\"y\",\"c\":\"z\"} | at most 2 properties | with 3 properties",
        delimiter = '|'
    )
    fun `property count diagnostics use singular only for one`(
        bound: String, limit: Int, json: String, expected: String, actual: String
    ) {
        val pattern = JSONObjectPattern(
            minProperties = limit.takeIf { bound == "min" },
            maxProperties = limit.takeIf { bound == "max" },
            additionalProperties = AdditionalProperties.FreeForm
        )

        val result = pattern.matches(parsedJSONObject(json), Resolver())

        assertThat(result.reportString()).contains(expected, actual).doesNotContain("1 properties")
    }

    @Test
    fun `an omission policy that cannot meet the minimum still fails during generation`() {
        val original = JSONObjectPattern(
            pattern = mapOf("id" to StringPattern(), "name?" to StringPattern()), minProperties = 2
        )

        assertThatThrownBy { original.generate(Resolver().withOnlyMandatoryKeysInJSONObject()) }
            .isInstanceOf(ContractException::class.java)
            .hasMessageContaining("Cannot generate a JSON object with at least 2 properties")
            .hasMessageContaining("the specification provides only 1 property")
    }

    @ParameterizedTest
    @CsvSource(
        "-1 | | true | false | minProperties -1 cannot be less than 0",
        " | -1 | true | false | maxProperties -1 cannot be less than 0",
        "3 | 2 | true | false | minimum property count 3 exceeds maximum property count 2",
        "0 | 1 | true | true | minimum property count 2 exceeds maximum property count 1",
        "3 | 4 | false | false | minimum property count 3 exceeds maximum property count 2",
        delimiter = '|'
    )
    fun `intrinsic object count conflicts are rejected at construction`(
        minimum: Int?, maximum: Int?, additional: Boolean, required: Boolean, message: String
    ) {
        val properties = if (required) {
            mapOf("id" to StringPattern(), "name" to StringPattern())
        } else {
            mapOf("id?" to StringPattern(), "name?" to StringPattern())
        }

        assertThatThrownBy {
            JSONObjectPattern(
                pattern = properties, minProperties = minimum, maxProperties = maximum,
                additionalProperties = if (additional) AdditionalProperties.FreeForm else AdditionalProperties.NoAdditionalProperties
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
    }

    @Test
    fun `a zero minimum does not require generating up to an unbounded maximum`() {
        val original = JSONObjectPattern(minProperties = 0, additionalProperties = AdditionalProperties.FreeForm)

        val value = original.generate(Resolver())

        assertThat(value.jsonObject).isEmpty()
    }

    @Test
    fun `a zero maximum permits an empty object despite optional declared and additional properties`() {
        val original = JSONObjectPattern(
            pattern = mapOf("name?" to StringPattern()), minProperties = 0, maxProperties = 0,
            additionalProperties = AdditionalProperties.FreeForm
        )
        val resolver = Resolver()

        val values = original.newBasedOn(resolver).map { it.generate(resolver) }.toList()

        assertThat(values).hasSize(1).allSatisfy { value ->
            assertThat(value.jsonObject).isEmpty()
            assertThat(original.matches(value, resolver).isSuccess()).isTrue()
        }
    }

    @ParameterizedTest
    @CsvSource(
        "min, 2, , typed, false, false",
        "min, , 2, typed, false, true",
        "max, 2, , typed, false, false",
        "max, , 2, typed, false, true",
        "min, 0, , free, false, true",
        "min, 1, , free, true, true",
        "min, , 1, free, true, true",
        "min, 1, 0, free, true, true",
        "max, 2, 3, closed, false, true",
        "max, 3, , closed, false, true",
        "max, , 3, closed, false, true",
        "max, 1, , closed, false, false",
        "min, 2, 1, closed, false, false",
        "max, 2, 1, closed, false, true"
    )
    fun `response count containment uses effective limits`(
        bound: String, oldLimit: Int?, newLimit: Int?, policy: String, required: Boolean, compatible: Boolean
    ) {
        val additionalProperties = when (policy) {
            "typed" -> AdditionalProperties.PatternConstrained(StringPattern())
            "free" -> AdditionalProperties.FreeForm
            "closed" -> AdditionalProperties.NoAdditionalProperties
            else -> error("Unknown additional-property policy: $policy")
        }
        val older = JSONObjectPattern(
            pattern = mapOf((if (required) "id" else "id?") to StringPattern(), "name?" to StringPattern()),
            minProperties = oldLimit.takeIf { bound == "min" },
            maxProperties = oldLimit.takeIf { bound == "max" },
            additionalProperties = additionalProperties
        )
        val newer = older.copy(
            minProperties = newLimit.takeIf { bound == "min" },
            maxProperties = newLimit.takeIf { bound == "max" }
        )

        val result = older.encompasses(newer, Resolver(), Resolver())

        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isEqualTo(compatible)
    }

    @ParameterizedTest
    @CsvSource("free, false", "typed, false", "free, true", "typed, true", "closed, true")
    fun `generated objects meet count bounds using declared and permitted additional properties`(policy: String, declared: Boolean) {
        val properties = if (declared)
            mapOf("id" to NumberPattern(), "name?" to StringPattern(), "active?" to BooleanPattern())
        else emptyMap()
        val additional = when (policy) {
            "free" -> AdditionalProperties.FreeForm
            "typed" -> AdditionalProperties.PatternConstrained(ExactValuePattern(StringValue("extra")))
            "closed" -> AdditionalProperties.NoAdditionalProperties
            else -> error("Unknown additional-property policy: $policy")
        }
        val original = JSONObjectPattern(properties, minProperties = 3, maxProperties = 3, additionalProperties = additional)
        val resolver = Resolver()

        val value = original.generate(resolver)

        assertThat(value.jsonObject).hasSize(3)
        val result = original.matches(value, resolver)
        assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `positive object variants satisfy the minimum using valid additional properties`(withRow: Boolean) {
        val original = JSONObjectPattern(
            pattern = mapOf("additionalProperty1" to NumberPattern(), "optional?" to BooleanPattern()),
            minProperties = 3,
            maxProperties = 4,
            additionalProperties = AdditionalProperties.PatternConstrained(ExactValuePattern(StringValue("extra")))
        )
        val resolver = Resolver()
        val variants = if (withRow) {
            original.newBasedOn(Row(), resolver).map { it.value }
        } else {
            original.newBasedOn(resolver)
        }

        val values = variants.map { it.generate(resolver) }.toList()

        assertThat(values).isNotEmpty.allSatisfy { value ->
            val result = original.matches(value, resolver)
            assertThat(result.isSuccess()).withFailMessage(result.reportString()).isTrue()
            val properties = (value as JSONObjectValue).jsonObject
            assertThat(properties.getValue("additionalProperty1")).isInstanceOf(NumberValue::class.java)
            assertThat(properties.filterKeys { it != "additionalProperty1" && it != "optional" }.values)
                .isNotEmpty.allMatch { it == StringValue("extra") }
        }
    }
}
