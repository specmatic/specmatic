package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class RegexBasedStringGeneratorTest {
    private val regex = "[a-z]+"

    @Nested
    inner class SuppliedAutomaton {
        @Test
        fun `uses the supplied language without compiling the regex label`() {
            val generator = RegexBasedStringGenerator("label", Automaton.makeString("ab"))
            assertThat(generator.regex).isEqualTo("label")
            assertThat(generator.isFinite).isTrue()
            assertThat(generator.isInfinite).isFalse()
            assertThat(generator.generateShortest()).isEqualTo("ab")
            assertThat(generator.generateLongest(10)).isEqualTo("ab")
            assertThat(generator.random(2, 2)).isEqualTo("ab")
        }

        @ParameterizedTest
        @CsvSource(value = ["0, 1", "3, 4"])
        fun `retains the empty string fallback when bounds exclude the supplied language`(minLength: Int, maxLength: Int) {
            val generator = RegexBasedStringGenerator("label", Automaton.makeString("ab"))
            assertThat(generator.random(minLength, maxLength)).isEmpty()
        }
    }

    @Test
    fun `warning message without constraints omits constraint text`() {
        val generator = RegexBasedStringGenerator(regex)

        val message = generator.warningMessage(0, REASONABLE_STRING_LENGTH).toString()

        assertThat(message).isEqualTo("WARNING: Could not generate a string based on $regex")
    }

    @Test
    fun `warning message includes constraint details when provided`() {
        val generator = RegexBasedStringGenerator(regex)

        val message = generator.warningMessage(1, 10).toString()

        assertThat(message).isEqualTo("WARNING: Could not generate a string based on $regex with minLength 1, maxLength 10")
    }

    @Test
    fun `warning message excludes zero minLength constraint`() {
        val generator = RegexBasedStringGenerator(regex)

        val message = generator.warningMessage(0, 10).toString()

        assertThat(message).isEqualTo("WARNING: Could not generate a string based on $regex with maxLength 10")
    }

    @Test
    fun `automaton allow mutate should always be disabled`() {
        RegexBasedStringGenerator("[a-z]+")
        assertThat(Automaton.setAllowMutate(false)).isFalse()
    }
}
