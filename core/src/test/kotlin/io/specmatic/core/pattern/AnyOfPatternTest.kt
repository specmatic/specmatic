package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.AdditionalProperties
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
}
