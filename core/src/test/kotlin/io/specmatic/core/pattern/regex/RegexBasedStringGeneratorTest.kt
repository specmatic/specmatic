package io.specmatic.core.pattern.regex

import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class RegexBasedStringGeneratorTest {
    private val regex = "[a-z]+"

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
}
