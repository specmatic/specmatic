package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class JSONArrayPatternKtTest {
    @Test
    fun `when there is one nullable in a list two array types should be generated`() {
        val arrayType = parsedPattern("""["(number?)", "(number)"]""")

        val patterns = arrayType.newBasedOn(Row(), Resolver()).toList().map { it.value as JSONArrayPattern }
        assertThat(patterns).hasSize(2)
        assertThat(patterns).contains(JSONArrayPattern(listOf<Pattern>(NullPattern, NumberPattern())))
        assertThat(patterns).contains(JSONArrayPattern(listOf<Pattern>(NullPattern, NumberPattern())))

        println(patterns.size)
        for (json in patterns) println(json)
    }

    @Nested
    @DisplayName("Given [optional, required]")
    inner class FirstCompulsorySecondRequired {
        private val combinations = allOrNothingListCombinations(listOf(sequenceOf(StringPattern(), null), sequenceOf(NumberPattern()))).toList()

        @Test
        fun `two results should be generated`() {
            assertThat(combinations).hasSize(2)
        }

        @Test
        fun `one result should have only the required type`() {
            assertThat(combinations).contains(listOf(NumberPattern()))
        }

        @Test
        fun `the other result should have both types with order preserved`() {
            assertThat(combinations).contains(listOf(StringPattern(), NumberPattern()))
        }
    }

    @Nested
    @DisplayName("Given [optional, required, optional, required]")
    inner class OneCompulsoryAndOneRequired {
        private val combinations: List<List<Pattern>> = allOrNothingListCombinations(
            listOf(
                sequenceOf(StringPattern(), null),
                sequenceOf(NumberPattern()),
                sequenceOf(BooleanPattern(), null),
                sequenceOf(DateTimePattern)
            )
        ).toList()

        @Test
        fun `two results should be generated`() {
            assertThat(combinations).hasSize(2)
        }

        @Test
        fun `one result should have only the required types with order preserved`() {
            assertThat(combinations).contains(listOf(NumberPattern(), DateTimePattern))
        }

        @Test
        fun `the other result should have all the types with order preserved`() {
            assertThat(combinations).contains(listOf(StringPattern(), NumberPattern(), BooleanPattern(), DateTimePattern))
        }
    }

    @Test
    fun `error message when the value is not an array`() {
        val arrayType: JSONArrayPattern = parsedPattern("""["(number)"]""") as JSONArrayPattern
        val nonArrayValue = StringValue("""not a json array""")

        val result = arrayType.matches(nonArrayValue, Resolver())

        println(result.reportString())
        assertThat(result.reportString()).contains("not a json array")
        assertThat(result.reportString()).contains("Expected json array")
    }
}
