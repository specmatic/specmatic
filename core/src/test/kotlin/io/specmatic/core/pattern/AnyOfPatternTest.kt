package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class AnyOfPatternTest {
    private val resolver = Resolver()

    @Test
    fun `matches succeeds when one subschema matches`() {
        val pattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))

        val result = pattern.matches(StringValue("hello"), resolver)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `matches fails when none of the subschemas match`() {
        val pattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))

        val result = pattern.matches(BooleanValue(true), resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `fixValue delegates to the first subschema`() {
        val pattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))

        val fixed = pattern.fixValue(NumberValue(10), resolver)

        assertThat(fixed).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `encompasses ensures old subschemas are covered`() {
        val newPattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))
        val oldPattern = AnyOfPattern(listOf(StringPattern()))

        val result = newPattern.encompasses(oldPattern, resolver, resolver, emptySet())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `encompasses fails when other pattern is not anyOf`() {
        val pattern = AnyOfPattern(listOf(StringPattern()))

        val result = pattern.encompasses(StringPattern(), resolver, resolver, emptySet())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `matches fails when object contains keys missing from all schemas`() {
        val pattern =
            AnyOfPattern(
                listOf(
                    JSONObjectPattern(
                        pattern = mapOf("id" to StringPattern()),
                        additionalProperties = AdditionalProperties.FreeForm,
                    ),
                    JSONObjectPattern(
                        pattern = mapOf("code" to NumberPattern()),
                        additionalProperties = AdditionalProperties.FreeForm,
                    ),
                ),
            )

        val value = JSONObjectValue(mapOf("id" to StringValue("123"), "extra" to StringValue("value")))

        val result = pattern.matches(value, resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.toReport().toText()).contains("Key(s) extra")
    }

    @Test
    fun `generate produces value matching one of the subschemas`() {
        val pattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))

        val generated = pattern.generate(resolver)

        assertThat(pattern.matches(generated, resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `newBasedOn returns patterns derived from each subschema`() {
        val pattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))

        val generatedPatterns = pattern.newBasedOn(Row(), resolver).map { it.value }.toList()

        assertThat(generatedPatterns.any { it is StringPattern }).isTrue()
        assertThat(generatedPatterns.any { it is NumberPattern }).isTrue()
    }

    @Test
    fun `matches succeeds when object fits only one of two object subschemas`() {
        val first = JSONObjectPattern(mapOf("id" to StringPattern()))
        val second = JSONObjectPattern(mapOf("code" to NumberPattern()))
        val anyOf = AnyOfPattern(listOf(first, second))

        val value = JSONObjectValue(mapOf("id" to StringValue("ABC")))

        assertThat(anyOf.matches(value, resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `matches succeeds when object fits both subschemas`() {
        val first = JSONObjectPattern(mapOf("id" to StringPattern()))
        val second = JSONObjectPattern(mapOf("id" to StringPattern(), "status?" to StringPattern()))
        val anyOf = AnyOfPattern(listOf(first, second))

        val value = JSONObjectValue(mapOf("id" to StringValue("XYZ")))

        assertThat(anyOf.matches(value, resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `discriminator based anyOf matches respective subschema`() {
        val alphaPattern =
            JSONObjectPattern(
                mapOf(
                    "type" to ExactValuePattern(StringValue("alpha"), discriminator = true),
                    "message" to StringPattern(),
                ),
                typeAlias = "(Alpha)",
            )
        val betaPattern =
            JSONObjectPattern(
                mapOf(
                    "type" to ExactValuePattern(StringValue("beta"), discriminator = true),
                    "count" to NumberPattern(),
                ),
                typeAlias = "(Beta)",
            )

        val anyOf =
            AnyOfPattern(
                listOf(alphaPattern, betaPattern),
                discriminator = Discriminator.create("type", setOf("alpha", "beta"), emptyMap()),
            )

        val alphaValue = JSONObjectValue(mapOf("type" to StringValue("alpha"), "message" to StringValue("hello")))
        val betaValue = JSONObjectValue(mapOf("type" to StringValue("beta"), "count" to NumberValue(5)))

        assertThat(anyOf.matches(alphaValue, resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(anyOf.matches(betaValue, resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `discriminator based anyOf generate produces matching value`() {
        val alphaPattern =
            JSONObjectPattern(
                mapOf(
                    "type" to ExactValuePattern(StringValue("alpha"), discriminator = true),
                    "message" to StringPattern(),
                ),
                typeAlias = "(Alpha)",
            )
        val betaPattern =
            JSONObjectPattern(
                mapOf(
                    "type" to ExactValuePattern(StringValue("beta"), discriminator = true),
                    "count" to NumberPattern(),
                ),
                typeAlias = "(Beta)",
            )

        val anyOf =
            AnyOfPattern(
                listOf(alphaPattern, betaPattern),
                discriminator = Discriminator.create("type", setOf("alpha", "beta"), emptyMap()),
            )

        val generated = anyOf.generate(resolver)

        assertThat(anyOf.matches(generated, resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(generated).isInstanceOf(JSONObjectValue::class.java)
        val typeValue = (generated as JSONObjectValue).jsonObject["type"] as StringValue
        assertThat(typeValue.string).isIn("alpha", "beta")
    }

    @Test
    fun `discriminator based anyOf newBasedOn derives both subschemas`() {
        val alphaPattern =
            JSONObjectPattern(
                mapOf(
                    "type" to ExactValuePattern(StringValue("alpha"), discriminator = true),
                    "message" to StringPattern(),
                ),
                typeAlias = "(Alpha)",
            )
        val betaPattern =
            JSONObjectPattern(
                mapOf(
                    "type" to ExactValuePattern(StringValue("beta"), discriminator = true),
                    "count" to NumberPattern(),
                ),
                typeAlias = "(Beta)",
            )

        val anyOf =
            AnyOfPattern(
                listOf(alphaPattern, betaPattern),
                discriminator = Discriminator.create("type", setOf("alpha", "beta"), emptyMap()),
            )

        val derivedPatterns = anyOf.newBasedOn(Row(), resolver).map { it.value }.toList()

        assertThat(derivedPatterns).hasSize(2)
        val derivedAliases = derivedPatterns.filterIsInstance<JSONObjectPattern>().mapNotNull { it.typeAlias }
        assertThat(derivedAliases).contains("(Alpha)", "(Beta)")
    }

    @Test
    fun `typeName describes constituent patterns`() {
        val pattern = AnyOfPattern(listOf(StringPattern(), NumberPattern()))

        assertThat(pattern.typeName).isEqualTo("(anyOf string or number)")
    }

    @Test
    fun `typeName for empty anyOf`() {
        val pattern = AnyOfPattern(emptyList())

        assertThat(pattern.typeName).isEqualTo("(anyOf)")
    }
}
