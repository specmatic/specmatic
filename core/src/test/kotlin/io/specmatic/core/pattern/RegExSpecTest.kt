package io.specmatic.core.pattern

import dk.brics.automaton.RegExp
import io.specmatic.core.value.StringValue
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class RegExSpecTest {
    data class RuntimeMatchCase(val regex: String, val matchingInputs: List<String>, val nonMatchingInputs: List<String> = emptyList()) {
        override fun toString(): String = "regex=/$regex/"
    }

    @Nested
    inner class UnanchoredPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#unanchoredPatternCases")
        fun `match anywhere in the input`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class StartAnchoredPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#startAnchoredPatternCases")
        fun `match from the beginning while allowing a suffix`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class EndAnchoredPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#endAnchoredPatternCases")
        fun `match through the end while allowing a prefix`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class FullyAnchoredPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#fullyAnchoredPatternCases")
        fun `retain both explicit boundaries`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class AlternationPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#alternationPatternCases")
        fun `retain anchor semantics independently across alternatives`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class EscapedAnchorPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#escapedAnchorPatternCases")
        fun `treat escaped anchors as literal characters`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class CharacterClassPatterns {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#characterClassPatternCases")
        fun `treat anchors inside character classes as characters`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class NestedWholeGroups {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#nestedWholeGroupCases")
        fun `apply boundaries around nested whole groups`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class EmptyAlternatives {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#emptyAlternativeCases")
        fun `match empty alternatives only when the original regex allows them`(case: RuntimeMatchCase) = assertRuntimeMatches(case)
    }

    @Nested
    inner class StandaloneWhitespaceShorthand {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#generatableWhitespaceCharacters")
        fun `generated regex accepts common Java and ECMAScript whitespace`(name: String, value: String) {
            val automaton = RegExp(RegExSpec("\\s").toString(), 0).toAutomaton()
            assertThat(automaton.run(value)).isTrue
        }

        @Test
        fun `reported reproducer rejects strings where vertical tab was previously treated as non-whitespace`() {
            val regex = "^\\s*\\S.*$"
            val spec = RegExSpec(regex)
            val generatedRegex = RegExp(spec.toString(), 0).toAutomaton()
            val previouslyInvalidValues = listOf(
                "\u000B",
                " \t\u000B",
                "\r\n\u000B",
                "\u000B\u000C",
            )

            assertThat(previouslyInvalidValues).allSatisfy { value ->
                assertThat(generatedRegex.run(value)).isFalse
                assertThat(spec.match(StringValue(value))).isFalse
            }

            val generated = spec.generateShortestStringOrRandom(1)
            assertThat(spec.match(StringValue(generated))).isTrue
        }
    }

    @Nested
    inner class StandaloneNonWhitespaceShorthand {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#ecmaWhitespaceCharacters")
        fun `generated regex excludes every ECMAScript whitespace character`(name: String, value: String) {
            val automaton = RegExp(RegExSpec("\\S").toString(), 0).toAutomaton()
            assertThat(automaton.run(value)).isFalse
        }

        @ParameterizedTest
        @ValueSource(strings = ["A", "0", "_", "-", "é"])
        fun `generated regex accepts representative non-whitespace characters`(value: String) {
            val automaton = RegExp(RegExSpec("\\S").toString(), 0).toAutomaton()
            assertThat(automaton.run(value)).isTrue
        }
    }

    @Nested
    inner class EscapedShorthandCharacters {
        @ParameterizedTest
        @MethodSource("io.specmatic.core.pattern.RegExSpecTest#escapedShorthandLiterals")
        fun `escaped literal backslashes remain unchanged`(regex: String, literal: String) {
            val cleanedRegex = RegExSpec(regex).toString()
            val automaton = RegExp(cleanedRegex, 0).toAutomaton()

            assertThat(cleanedRegex).isEqualTo(regex)
            assertThat(automaton.run(literal)).isTrue
            assertThat(automaton.run(" ")).isFalse
        }
    }

    private fun assertRuntimeMatches(case: RuntimeMatchCase) {
        val regex = RegExSpec(case.regex)
        case.matchingInputs.forEach { input ->
            assertThat(regex.match(StringValue(input)))
                .withFailMessage("Expected /${case.regex}/ to match $input")
                .isTrue
        }

        case.nonMatchingInputs.forEach { input ->
            assertThat(regex.match(StringValue(input)))
                .withFailMessage("Expected /${case.regex}/ not to match $input")
                .isFalse
        }
    }

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

    @Test
    fun `match should not allow dot to cross newlines`() {
        val regExSpec = RegExSpec("^a.b$")
        assertThat(regExSpec.match(StringValue("a\nb"))).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "^foo; foo",
            "foo$; foo",
            "^foo|bar$; foo|bar",
        ],
    )
    fun `whole-value generation does not add unanchored sides`(regex: String, expectedRegex: String) {
        assertThat(RegExSpec(regex, RegexMatchMode.WHOLE_VALUE).toString()).isEqualTo(expectedRegex)
    }

    @Test
    fun `generation for unescaped dot must not accept line terminators that match rejects`() {
        val cleaned = RegExSpec(".").toString()
        val automaton = RegExp(cleaned, 0).toAutomaton()
        val spec = RegExSpec(".")

        assertThat(automaton.run("\n")).isFalse()
        assertThat(automaton.run("\r")).isFalse()
        assertThat(automaton.run("\u0085")).isFalse()
        assertThat(automaton.run("\u2028")).isFalse()
        assertThat(automaton.run("\u2029")).isFalse()
        assertThat(automaton.run("x")).isTrue()
        assertThat(spec.match(StringValue("\n"))).isFalse()
        assertThat(spec.match(StringValue("x"))).isTrue()
    }

    @Test
    fun `escaped dots and dots inside character classes stay literal`() {
        assertThat(RegExSpec("a\\.b").toString()).isEqualTo("a\\.b")
        assertThat(RegExSpec("[a.b]").toString()).isEqualTo("[a.b]")
        assertThat(RegExp(RegExSpec("[a.b]").toString(), 0).toAutomaton().run(".")).isTrue()
        assertThat(RegExp(RegExSpec("a\\.b").toString(), 0).toAutomaton().run("a.b")).isTrue()
        assertThat(RegExp(RegExSpec("a\\.b").toString(), 0).toAutomaton().run("axb")).isFalse()
    }

    @Test
    fun `generated string for a regex containing a dot must match without DOTALL`() {
        val spec = RegExSpec("^a.b$")
        val generated = spec.generateRandomString(3, 3).toStringLiteral()
        assertThat(spec.match(StringValue(generated))).isTrue()
        assertThat(generated).matches("a.b")
    }

    @ParameterizedTest
    @CsvSource(
        "^[A-Z]{5,10}\$; [A-Z]{5,10}",
        "[A-Z]{,10}; [A-Z]{0,10}",
        "$WORD_BOUNDARY[A-Z]{5,10}$WORD_BOUNDARY; [A-Z]{5,10}",
        "A-Z\\s0-9; 'A-Z[ \t\n\u000B\u000C\r]0-9'",
        "A-Z\\d0-9; A-Z[0-9]0-9",
        "A-Z\\w0-9; A-Z[a-zA-Z_0-9]0-9",
        "a[A-Z\\s0-9]b; 'a[A-Z \t\n\u000B\u000C\r0-9]b'",
        "a[A-Z\\da-z]b; a[A-Z0-9a-z]b",
        "a[A-Z\\w0-9]b; a[A-Za-zA-Z_0-90-9]b",
        "a[^A-Z\\s0-9]b; 'a[^A-Z \t\n\u000B\u000C\r0-9]b'",
        "a[^A-Z\\da-z]b; a[^A-Z0-9a-z]b",
        "a[^A-Z\\w0-9]b; a[^A-Za-zA-Z_0-90-9]b",
        "A-Z\\S0-9; 'A-Z[^ \t\n\u000B\u000C\r\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]0-9'",
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

    companion object {
        @JvmStatic
        fun generatableWhitespaceCharacters(): Stream<Arguments> = Stream.of(
            Arguments.of("SPACE U+0020", "\u0020"),
            Arguments.of("TAB U+0009", "\u0009"),
            Arguments.of("LF U+000A", "\u000A"),
            Arguments.of("VERTICAL TAB U+000B", "\u000B"),
            Arguments.of("FORM FEED U+000C", "\u000C"),
            Arguments.of("CR U+000D", "\u000D"),
        )

        @JvmStatic
        fun ecmaWhitespaceCharacters(): Stream<Arguments> = Stream.of(
            Arguments.of("TAB U+0009", "\u0009"),
            Arguments.of("LF U+000A", "\u000A"),
            Arguments.of("VERTICAL TAB U+000B", "\u000B"),
            Arguments.of("FORM FEED U+000C", "\u000C"),
            Arguments.of("CR U+000D", "\u000D"),
            Arguments.of("SPACE U+0020", "\u0020"),
            Arguments.of("NO-BREAK SPACE U+00A0", "\u00A0"),
            Arguments.of("OGHAM SPACE MARK U+1680", "\u1680"),
            Arguments.of("EN QUAD U+2000", "\u2000"),
            Arguments.of("EM QUAD U+2001", "\u2001"),
            Arguments.of("EN SPACE U+2002", "\u2002"),
            Arguments.of("EM SPACE U+2003", "\u2003"),
            Arguments.of("THREE-PER-EM SPACE U+2004", "\u2004"),
            Arguments.of("FOUR-PER-EM SPACE U+2005", "\u2005"),
            Arguments.of("SIX-PER-EM SPACE U+2006", "\u2006"),
            Arguments.of("FIGURE SPACE U+2007", "\u2007"),
            Arguments.of("PUNCTUATION SPACE U+2008", "\u2008"),
            Arguments.of("THIN SPACE U+2009", "\u2009"),
            Arguments.of("HAIR SPACE U+200A", "\u200A"),
            Arguments.of("LINE SEPARATOR U+2028", "\u2028"),
            Arguments.of("PARAGRAPH SEPARATOR U+2029", "\u2029"),
            Arguments.of("NARROW NO-BREAK SPACE U+202F", "\u202F"),
            Arguments.of("MEDIUM MATHEMATICAL SPACE U+205F", "\u205F"),
            Arguments.of("IDEOGRAPHIC SPACE U+3000", "\u3000"),
            Arguments.of("ZERO WIDTH NO-BREAK SPACE U+FEFF", "\uFEFF"),
        )

        @JvmStatic
        fun escapedShorthandLiterals(): Stream<Arguments> = Stream.of(
            Arguments.of("\\\\s", "\\s"),
            Arguments.of("\\\\S", "\\S"),
        )

        @JvmStatic
        fun unanchoredPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = "ABC",
                matchingInputs = listOf("ABC", "ABCxx", "xxABC", "xxABCxx"),
                nonMatchingInputs = listOf("ABX"),
            ),
            RuntimeMatchCase(regex = "", matchingInputs = listOf("", "anything")),
        )

        @JvmStatic
        fun startAnchoredPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = "^ABC",
                matchingInputs = listOf("ABC", "ABCxx"),
                nonMatchingInputs = listOf("xxABC", "xxABCxx"),
            ),
            RuntimeMatchCase(
                regex = "^[A-Z]{3}",
                matchingInputs = listOf("ABC", "ABCxxxx", "ABC-anything"),
                nonMatchingInputs = listOf("xxABC"),
            ),
        )

        @JvmStatic
        fun endAnchoredPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = """ABC$""",
                matchingInputs = listOf("ABC", "xxABC"),
                nonMatchingInputs = listOf("ABCxx", "xxABCxx"),
            ),
            RuntimeMatchCase(
                regex = """[A-Z]{3}$""",
                matchingInputs = listOf("ABC", "xxxxABC"),
                nonMatchingInputs = listOf("ABCxxxx"),
            ),
        )

        @JvmStatic
        fun fullyAnchoredPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = """^ABC$""",
                matchingInputs = listOf("ABC"),
                nonMatchingInputs = listOf("xxABCxx", "ABCxx", "xxABC"),
            ),
            RuntimeMatchCase(
                regex = """^[A-Z]{3}$""",
                matchingInputs = listOf("ABC"),
                nonMatchingInputs = listOf("ABc", "ABCD", "xxABC"),
            ),
        )

        @JvmStatic
        fun alternationPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = "foo|bar",
                matchingInputs = listOf("foo", "xxfoo", "barxx"),
                nonMatchingInputs = listOf("baz"),
            ),
            RuntimeMatchCase(
                regex = """^ABC$|XYZ""",
                matchingInputs = listOf("ABC", "xxXYZxx"),
                nonMatchingInputs = listOf("xxABCxx", "ABCxx"),
            ),
            RuntimeMatchCase(
                regex = """^ABC|XYZ$""",
                matchingInputs = listOf("ABC", "ABCxx", "XYZ", "xxXYZ"),
                nonMatchingInputs = listOf("xxABC", "XYZxx"),
            ),
        )

        @JvmStatic
        fun escapedAnchorPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = """\^foo\$""",
                matchingInputs = listOf("^foo$", $$"xx^foo$xx"),
                nonMatchingInputs = listOf("foo", $$"xxfoo$xx"),
            ),
        )

        @JvmStatic
        fun characterClassPatternCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = """[a^$]+""",
                matchingInputs = listOf("a", "^", "$", $$"x^$a"),
                nonMatchingInputs = listOf("xyz"),
            ),
            RuntimeMatchCase(
                regex = """[^$]+""",
                matchingInputs = listOf("abc"),
                nonMatchingInputs = listOf("$"),
            ),
        )

        @JvmStatic
        fun nestedWholeGroupCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = """^((foo|bar))$""",
                matchingInputs = listOf("foo", "bar"),
                nonMatchingInputs = listOf("foobar", "xxfoo"),
            ),
        )

        @JvmStatic
        fun emptyAlternativeCases(): Stream<RuntimeMatchCase> = Stream.of(
            RuntimeMatchCase(
                regex = """^$|^[A-Za-z0-9._\-]{1,64}$""",
                matchingInputs = listOf("", "abc", "a_b.c-1"),
                nonMatchingInputs = listOf("a!"),
            ),
            RuntimeMatchCase(
                regex = """^(foo|)$""",
                matchingInputs = listOf("", "foo"),
                nonMatchingInputs = listOf("bar", "foobar"),
            ),
        )
    }
}
