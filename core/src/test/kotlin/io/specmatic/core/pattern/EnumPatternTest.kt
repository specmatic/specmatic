package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Dictionary
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.UseDefaultExample
import io.specmatic.core.value.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnumPatternTest {
    @Nested
    inner class Construction {
        @Test
        fun `it should accept values of the same type`() {
            assertDoesNotThrow {
                EnumPattern(listOf(StringValue("01"), StringValue("02")))
            }
        }

        @Test
        fun `it should not accept values of the different types`() {
            assertThrows<ContractException> {
                EnumPattern(listOf(StringValue("01"), NumberValue(2)))
            }
        }

        @Test
        fun `it should accept null alongside other homogenous value when nullable is true`() {
            assertDoesNotThrow {
                EnumPattern(listOf(StringValue("01"), StringValue("02"), NullValue), nullable = true)
            }
        }
    }

    @Nested
    inner class GettingValues {
        @Test
        fun `it should generate a value from the given values`() {
            val enum = EnumPattern(listOf(StringValue("01"), StringValue("02")))

            val generatedValue = enum.generate(Resolver())

            assertThat(generatedValue).isIn(listOf(StringValue("01"), StringValue("02")))
        }

        @Test
        fun `it should parse a new value to the enum type`() {
            val enum = EnumPattern(listOf(NumberValue(1)))

            val parsedValue = enum.parse("03", Resolver())

            assertThat(parsedValue).isEqualTo(NumberValue(3))
        }

        @Test
        fun `it should fail to parse a new value NOT matching the enum type`() {
            val enum = EnumPattern(listOf(NumberValue(1)))

            assertThrows<ContractException> { enum.parse("not a number", Resolver()) }
        }
    }

    @Nested
    @Tag(GENERATION)
    inner class TestGeneration {
        @Test
        fun `it should generate new patterns for all enum values when the row is empty`() {
            val enum = EnumPattern(listOf(StringValue("01"), StringValue("02")))

            val newPatterns = enum.newBasedOn(Row(), Resolver()).map { it.value }.toList()

            assertThat(newPatterns).containsExactlyInAnyOrder(
                ExactValuePattern(StringValue("01")),
                ExactValuePattern(StringValue("02"))
            )
        }

        @Test
        fun `it should only pick the value in the row when the row is NOT empty`() {
            val jsonPattern =
                JSONObjectPattern(mapOf("type" to EnumPattern(listOf(StringValue("01"), StringValue("02")))))

            val newPatterns = jsonPattern.newBasedOn(Row(listOf("type"), values = listOf("01")), Resolver())
                .map { it.value as JSONObjectPattern }

            val values = newPatterns.map { it.generate(Resolver()) }.toList()

            val strings = values.map { it.jsonObject.getValue("type") as StringValue }

            assertThat(strings).containsExactly(
                StringValue("01")
            )
        }

        @Test
        fun `it should use the inline example if present`() {
            val enum = EnumPattern(listOf(StringValue("01"), StringValue("02")), example = "01")
            val patterns =
                enum.newBasedOn(Row(), Resolver(defaultExampleResolver = UseDefaultExample)).map { it.value }.toList()

            assertThat(patterns).containsExactly(
                ExactValuePattern(StringValue("01"))
            )
        }

        @Test
        @Tag(GENERATION)
        fun `it should generate negative values for what is in the row`() {
            val jsonPattern = EnumPattern(listOf(StringValue("01"), StringValue("02")))

            val newPatterns = jsonPattern.negativeBasedOn(Row(), Resolver()).map { it.value }.toList()

            val negativeTypes = newPatterns.map { it.typeName }
            println(negativeTypes)

            assertThat(negativeTypes).containsExactlyInAnyOrder(
                "null",
                "number",
                "boolean"
            )
        }
    }

    private fun toStringEnum(vararg items: String): EnumPattern {
        return EnumPattern(items.map { StringValue(it) })
    }

    @Nested
    inner class BackwardCompatibility {
        private val enum = toStringEnum("sold", "available")

        @Test
        fun `enums with more are backward compatible than enums with less`() {
            val enumWithMore = toStringEnum("sold", "available", "reserved")
            assertThat(enum.encompasses(enumWithMore, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
        }

        @Test
        fun `enums with less are not backward compatible than enums with more`() {
            val enumWithLess = toStringEnum("sold")
            assertThat(enum.encompasses(enumWithLess, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class ErrorMessage {
        @Test
        fun `enum error message should feature the expected values`() {
            val result: Result = EnumPattern(
                listOf(
                    StringValue("01"),
                    StringValue("02")
                )
            ).encompasses(
                StringPattern(), Resolver(), Resolver()
            )

            val resultText = result.reportString()

            println(resultText)

            assertThat(resultText).contains("01")
            assertThat(resultText).contains("02")
        }
    }

    @Test
    fun `should be able to generate a value when pattern token of itself is supplied`() {
        val enumValues: List<Value> = listOf("Cat", "Dog", "Fish").map { StringValue(it) }
        val enumPattern = EnumPattern(enumValues, typeAlias = "(Test)")
        val resolver = Resolver(newPatterns = mapOf("(Test)" to enumPattern))
        val value = StringValue("(Test)")
        val filledInValue = enumPattern.fillInTheBlanks(value, resolver).value

        assertThat(enumValues).contains(filledInValue)
    }

    @Test
    fun `should use dictionary value when filling in from pattern token`() {
        val enumValues: List<Value> = listOf("Cat", "Dog", "Fish").map { StringValue(it) }
        val enumPattern = EnumPattern(enumValues, typeAlias = "(AnimalType)")
        val jsonPattern = JSONObjectPattern(mapOf("type" to enumPattern), typeAlias = "(Test)")

        val dictionary = "Test: { type: Dog }".let(Dictionary::fromYaml)
        val resolver = Resolver(newPatterns = mapOf("(AnimalType)" to enumPattern), dictionary = dictionary)
        val value = JSONObjectValue(mapOf("type" to StringValue("(AnimalType)")))
        val filledInValue = jsonPattern.fillInTheBlanks(value, resolver).value

        assertThat(filledInValue).isEqualTo(JSONObjectValue(mapOf("type" to StringValue("Dog"))))
    }

    @Test
    fun `should be able to fix invalid values`() {
        val enumValues: List<Value> = listOf("Cat", "Dog", "Fish").map { StringValue(it) }
        val enumPattern = EnumPattern(enumValues, typeAlias = "(AnimalType)")
        val pattern = JSONObjectPattern(mapOf("type" to enumPattern), typeAlias = "(Test)")
        val dictionary = "Test: { type: Dog }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary)
        val invalidValues = listOf(
            StringValue("Unknown"),
            NumberValue(999),
            NullValue
        )

        assertThat(invalidValues).allSatisfy {
            val fixedValue = pattern.fixValue(JSONObjectValue(mapOf("type" to it)), resolver)
            fixedValue as JSONObjectValue
            assertThat(fixedValue.jsonObject["type"]).isIn(enumValues)
        }
    }
}