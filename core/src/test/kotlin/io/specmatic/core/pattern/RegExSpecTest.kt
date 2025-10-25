package io.specmatic.core.pattern

import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class RegExSpecTest {
    @Test
    fun `should not allow construction with invalid regex`() {
        val invalidRegex = "/^a{10}\$/"
        assertThrows<Exception> { RegExSpec(invalidRegex) }
            .also { assertThat(it.message).isEqualTo("Invalid regex $invalidRegex. OpenAPI follows ECMA-262 regular expressions, which do not support / / delimiters like those used in many programming languages") }
    }

    @Test
    fun `should not allow construction with minLength greater that what is possible with regex`() {
        val tenOccurrencesOfAlphabetA = "^a{10}\$"
        val minLength = 15
        assertThrows<Exception> { RegExSpec(tenOccurrencesOfAlphabetA).validateMinLength(minLength) }
            .also { assertThat(it.message).isEqualTo("minLength $minLength cannot be greater than the length of longest possible string that matches regex $tenOccurrencesOfAlphabetA") }
    }

    @Test
    fun `should not allow construction with maxLength lesser that what is possible with regex`() {
        val tenOccurrencesOfAlphabetA = "^a{10}\$"
        val maxLength = 8
        assertThrows<Exception> { RegExSpec(tenOccurrencesOfAlphabetA).validateMaxLength(maxLength) }
            .also { assertThat(it.message).isEqualTo("maxLength $maxLength cannot be less than the length of shortest possible string that matches regex $tenOccurrencesOfAlphabetA") }
    }

    @Test
    fun `should throw an exception with the regex parse failure from the regex library` () {
        assertThatThrownBy { RegExSpec("yes|no|") }.hasMessageContaining("unexpected end-of-string")
    }

    @Test
    fun `minLength greater than upper bound in the regex should not be accepted`() {
        val regex = "[A-Z]{10,20}"
        val minLength = 21
        assertThatThrownBy {
            RegExSpec(regex).validateMinLength(minLength)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("minLength $minLength cannot be greater than the length of longest possible string that matches regex $regex")
    }

    @ParameterizedTest
    @CsvSource(
        "[a-zA-Z0-9]{1,3}; 1; 1",
        "[a-zA-Z0-9]{1,3}; 2; 2",
        "[a-zA-Z0-9]{1,3}; 0; 1",
        "[a-zA-Z0-9]{1,}; 1; 1",
        "[a-zA-Z0-9]{1,}; 30; 30",
        "[a-zA-Z0-9]*; 0; 0",
        "[a-zA-Z0-9]*; 30; 30",
        "[a-zA-Z0-9]+; 1; 1",
        "[a-zA-Z0-9]+; 30; 30",
        "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}; 3; 3",
        "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}; 2; 3",
        "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}; 5; 5",
        delimiterString = "; "
    )
    fun `should generate min length based on the regex and min length`(regex: String, minLen: Int, expectedLength: Int) {
        val shortestString = RegExSpec(regex).generateShortestStringOrRandom(minLen)
        assertThat(shortestString).hasSize(expectedLength)
    }

    @Test
    fun `should generate random min length string when regex is null`() {
        val randomString = RegExSpec(null).generateShortestStringOrRandom(10)
        assertThat(randomString).hasSize(10)
    }

    @ParameterizedTest
    @CsvSource(
        "[a-zA-Z0-9]{1,3}; 3; 3",
        "[a-zA-Z0-9]{1,3}; 2; 2",
        "[a-zA-Z0-9]{1,3}; 4; 3",
        "[a-zA-Z0-9]{1,}; 30; 30",
        "[a-zA-Z0-9]*; 30; 30",
        "[a-zA-Z0-9]+; 30; 30",
        "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}; 7; 7",
        "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}; 6; 6",
        "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}; 8; 7",
        delimiterString = "; "
    )
    fun `should generate max length based on the regex and max length`(regex: String, max: Int, expectedLength: Int) {
        val longestString = RegExSpec(regex).generateLongestStringOrRandom(max)
        assertThat(longestString).hasSize(expectedLength)
    }

    @Test
    fun `should generate random max length string when regex is null`() {
        val randomString = RegExSpec(null).generateLongestStringOrRandom(10)
        assertThat(randomString).hasSize(10)
    }

    @Test
    fun `should strip out word boundary in regex`() {
        val possibleValues = "Cat|Dog|Lion|Tiger"
        val regExSpec = RegExSpec("$WORD_BOUNDARY($possibleValues)$WORD_BOUNDARY")
        possibleValues.split("|").forEach { assertThat(regExSpec.match(StringValue(it))).isTrue }
    }

    @ParameterizedTest
    @CsvSource(
        "^[A-Z]{5,10}\$; [A-Z]{5,10}",
        "[A-Z]{,10}; [A-Z]{0,10}",
        "$WORD_BOUNDARY[A-Z]{5,10}$WORD_BOUNDARY; [A-Z]{5,10}",
        "A-Z\\s0-9; 'A-Z[ \t\n\u000c\r]0-9'",
        "A-Z\\d0-9; A-Z[0-9]0-9",
        "A-Z\\w0-9; A-Z[a-zA-Z_0-9]0-9",
        "a[A-Z\\s0-9]b; 'a[A-Z \t\n\u000c\r0-9]b'",
        "a[A-Z\\da-z]b; a[A-Z0-9a-z]b",
        "a[A-Z\\w0-9]b; a[A-Za-zA-Z_0-90-9]b",
        "a[^A-Z\\s0-9]b; 'a[^A-Z \t\n\u000c\r0-9]b'",
        "a[^A-Z\\da-z]b; a[^A-Z0-9a-z]b",
        "a[^A-Z\\w0-9]b; a[^A-Za-zA-Z_0-90-9]b",
        "A-Z\\S0-9; 'A-Z[^ \t\n\u000c\r]0-9'",
        "A-Z\\D0-9; A-Z[^0-9]0-9",
        "A-Z\\W0-9; A-Z[^a-zA-Z_0-9]0-9",
        // TODO: Revisit Complementary meta sequence expansion in an character array
        // "a[A-Z\\S0-9]b; a[A-Z\\S0-9]b",
        // "a[A-Z\\Da-z]b; a[A-Z\\Da-z]b",
        // "a[A-Z\\W0-9]b; a[A-Z\\W0-9]b",
        delimiterString = "; "
    )
    fun `cleans up the regex at construction`(inputRegex: String, expectedRegex: String) {
        val regExSpec = RegExSpec(inputRegex)
        val toString = regExSpec.toString()
        assertThat(toString).isEqualTo(expectedRegex)
    }

    @ParameterizedTest
    @CsvSource(
        "null; 3; null; 5; 5",
        "null; 3; 4; 4; 4",
        "null; 3; 10; 5; 5",
        "^[A-Z]{5,10}\$; 5; 10; 5; 10",
        "[A-Z]{,10}; 0; 10; 0; 10",
        "[A-Z]{3,}; 0; 10; 3; 10",
        delimiterString = "; "
    )
    fun `Generate random string when regex is empty`(regex: String, min: Int, max: String, expectedMinLen: Int, expectedMaxLen: Int) {
        val generatedString = RegExSpec(regex.takeIf { it != "null" }).generateRandomString(min, max.toIntOrNull()).toStringLiteral()
        assertThat(generatedString.length)
            .isGreaterThanOrEqualTo(expectedMinLen)
            .isLessThanOrEqualTo(expectedMaxLen)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            // PRIMITIVES
            "[a-z]*",
            "[A-Z]*",
            "[0-9]*",
            "[a-z]+",
            "[a-z]{3}",
            "[a-z]{1,3}",
            "[a-z]{1,}",
            "[a-z]?",
            "Cat|Dog",
            "\\w{5,10}",
            "\\W{5,10}",
            "\\d{5,10}",
            "\\D{5,10}",
//            "[\\s]{5,10}", # FIX
//            "[\\S]{5,10}", # FIX
            ".",

            // COMBINATIONS
            "^hello-world$",
            "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}",
            "^(Cat|Dog|Lion|Tiger)$",
            "123\\d{3}",
            "v(\\d{3})\\.\\d{3}\\.\\d{3}",
            "[a-zA-Z0-9]{1,3}",
            "[a-zA-Z0-9]{1,}",
            "[a-zA-Z0-9]*",
            "[a-zA-Z0-9]+",
            "[a-zA-Z0-9]{3}",
            "[a-zA-Z0-9]?",
            "[a-zA-Z0-9]{1,3}-[a-zA-Z0-9]{1,3}",
        ],
    )
    fun `string generated by RegExSpec should match the regex as per Javas regex library`(regex: String) {
        val generatedString = (0..5).map { RegExSpec(regex).generateRandomString(1).toStringLiteral() }
        assertThat(generatedString).allSatisfy {
            assertThat(it).matches(regex)
        }
    }
}
