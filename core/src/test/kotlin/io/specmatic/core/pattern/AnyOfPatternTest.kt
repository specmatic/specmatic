package io.specmatic.core.pattern

import io.specmatic.core.DefaultMismatchMessages
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.toViolationReportString
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
    fun `encompasses succeeds when other pattern is anyPattern with same options`() {
        val newPattern =
            AnyOfPattern(
                listOf(
                    ListPattern(StringPattern()),
                    NullPattern
                )
            )
        val oldPattern =
            AnyPattern(
                pattern = listOf(
                    ListPattern(StringPattern()),
                    NullPattern
                ),
                discriminator = null,
                extensions = emptyMap()
            )

        val result = newPattern.encompasses(oldPattern, resolver, resolver, emptySet())

        assertThat(result).isInstanceOf(Result.Success::class.java)
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
        assertThat(result.toReport().toText()).isEqualToIgnoringWhitespace("""
        ${toViolationReportString(
            breadCrumb = "extra",
            details = "Key 'extra' is not declared in any anyOf option",
            StandardRuleViolation.ANY_OF_UNKNOWN_KEY
        )}
        """.trimIndent())
    }

    @Test
    fun `matches fails when object contains keys do not match any keys from any of the schemas`() {
        val pattern =
            AnyOfPattern(
                listOf(
                    JSONObjectPattern(
                        pattern = mapOf("common" to StringPattern()),
                        additionalProperties = AdditionalProperties.FreeForm,
                    ),
                    JSONObjectPattern(
                        pattern = mapOf("common" to NumberPattern()),
                        additionalProperties = AdditionalProperties.FreeForm,
                    ),
                ),
            )

        val value = JSONObjectValue(mapOf("common" to BooleanValue(false)))
        val result = pattern.matches(value, resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.toReport().toText()).containsIgnoringWhitespaces("""
        ${toViolationReportString(
            breadCrumb = "common",
            details = "Key 'common' did not match any anyOf option that declares it",
            StandardRuleViolation.ANY_OF_NO_MATCHING_SCHEMA
        )}
        ${toViolationReportString(
            breadCrumb = "common",
            details = DefaultMismatchMessages.typeMismatch("string", "false", "boolean"),
            StandardRuleViolation.TYPE_MISMATCH
        )}
        ${toViolationReportString(
            breadCrumb = "common",
            details = DefaultMismatchMessages.typeMismatch("number", "false", "boolean"),
            StandardRuleViolation.TYPE_MISMATCH
        )}
        """.trimIndent())
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

    @Test
    fun `matches reports overlapping pattern failure when key violates schema`() {
        val idPattern =
            JSONObjectPattern(
                pattern = mapOf("id" to StringPattern()),
                additionalProperties = AdditionalProperties.FreeForm,
            )
        val cidPattern =
            JSONObjectPattern(
                pattern = mapOf("cid" to StringPattern(maxLength = 10)),
            )
        val newIdPattern = JSONObjectPattern(pattern = mapOf("id" to NumberPattern()))

        val anyOf = AnyOfPattern(listOf(idPattern, cidPattern, newIdPattern))

        val value =
            JSONObjectValue(
                mapOf(
                    "id" to StringValue("abc123"),
                    "cid" to StringValue("abcdefghijklmnopqrstuvwxyz"),
                ),
            )

        val result = anyOf.matches(value, resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val report = result.toReport().toText()
        assertThat(report).contains("cid").contains("maxLength 10")
    }

    @Test
    fun `matches succeeds when overlapping key schema succeeds`() {
        val idPattern =
            JSONObjectPattern(
                pattern = mapOf("id" to StringPattern()),
                additionalProperties = AdditionalProperties.FreeForm,
            )
        val cidPattern =
            JSONObjectPattern(
                pattern = mapOf("cid" to StringPattern(maxLength = 10)),
            )
        val newIdPattern = JSONObjectPattern(pattern = mapOf("id" to NumberPattern()))

        val anyOf = AnyOfPattern(listOf(idPattern, cidPattern, newIdPattern))

        val value = JSONObjectValue(mapOf("id" to NumberValue(10)))

        val result = anyOf.matches(value, resolver)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
