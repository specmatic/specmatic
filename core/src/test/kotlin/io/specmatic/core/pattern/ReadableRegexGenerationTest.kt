package io.specmatic.core.pattern

import io.specmatic.core.value.StringValue
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import java.util.stream.Stream

class ReadableRegexGenerationTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "^(?=.*[^A-Za-z0-9]).{4}$",
        "^(?![A-Za-z0-9]{4}).{4}$",
        "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{4}$",
    ])
    fun `lookaround constraints retain their generation choices`(regex: String) {
        val spec = RegExSpec(regex)
        val generated = listOf(
            spec.generateShortestStringOrRandom(0),
            spec.generateLongestStringOrRandom(4),
        ) + List(20) { spec.generateRandomString(4, 4).toStringLiteral() }

        assertThat(generated).allSatisfy { value ->
            assertThat(value).hasSize(4)
            assertThat(value).matches(regex)
            assertThat(value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }).isTrue()
            assertThat(spec.match(StringValue(value))).isTrue()
        }
    }

    @Test
    fun `impossible lengths retain the original regex in generation warnings`() {
        val spec = RegExSpec("\\D{2}")

        val (output, generated) = captureStandardOutput {
            spec.generateRandomString(3, 3).toStringLiteral()
        }

        assertThat(generated).isEmpty()
        assertThat(output).contains("WARNING: Could not generate a string based on [^0-9]{2} with minLength 3, maxLength 3")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "..; 0; 2; 2; 2",
            ".{80}; 0; 80; 80; 80",
            ".{2,5}; 0; 10; 2; 5",
            ".+; 0; 80; 1; 80",
            ".*; 0; 80; 0; 80",
            ".*; 50; 80; 50; 80",
            "\\S*; 0; 80; 0; 80",
            "(?:.{2}|.{5}); 0; 10; 2; 5",
            "(?:\\S.){2}; 0; 4; 4; 4",
            "(?:(?:\\S.){2}); 0; 4; 4; 4",
        ],
    )
    fun `repetition preserves length objectives and stays within preferred characters`(
        regex: String, minimum: Int, maximum: Int, shortestLength: Int, longestLength: Int,
    ) {
        val spec = RegExSpec(regex, RegexMatchMode.WHOLE_VALUE)
        val shortest = spec.generateShortestStringOrRandom(minimum)
        val longest = spec.generateLongestStringOrRandom(maximum)
        val random = spec.generateRandomString(longestLength, longestLength).toStringLiteral()

        assertThat(shortest).hasSize(shortestLength)
        assertThat(longest).hasSize(longestLength)
        assertThat(random).hasSize(longestLength)
        assertThat(listOf(shortest, longest, random)).allSatisfy { value ->
            assertThat(value).matches("[A-Za-z0-9]*")
            assertThat(spec.match(StringValue(value))).isTrue()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["^ABC", "ABC$", "^ABC|XYZ$"])
    fun `automatically added prefixes and suffixes use preferred characters`(regex: String) {
        val spec = RegExSpec(regex)
        val shortest = spec.generateShortestStringOrRandom(0)
        val longest = spec.generateLongestStringOrRandom(12)
        val random = spec.generateRandomString(12, 12).toStringLiteral()

        assertThat(shortest).hasSize(3)
        assertThat(longest).hasSize(12)
        assertThat(random).hasSize(12)
        assertThat(listOf(shortest, longest, random)).allSatisfy { value ->
            assertThat(value).matches("[A-Za-z0-9]+")
            assertThat(spec.match(StringValue(value))).isTrue()
        }
    }

    @Test
    fun `explicit control characters remain eligible in alternatives`() {
        val spec = RegExSpec("[\u0001]|.", RegexMatchMode.WHOLE_VALUE)

        assertThat(spec.generateShortestStringOrRandom(0)).isEqualTo("\u0001")
        assertThat(spec.generateLongestStringOrRandom(1)).isEqualTo("z")
        assertThat(spec.match(StringValue("\u0001"))).isTrue()
    }

    @Test
    fun `explicit negated and whitespace classes retain their character choices`() {
        assertThat(RegExSpec("[^a]").generateShortestStringOrRandom(0)).isEqualTo("\u0000")
        assertThat(RegExSpec("[\\s]").generateShortestStringOrRandom(0)).isEqualTo("\t")
    }

    @ParameterizedTest
    @MethodSource("literalCases")
    fun `explicit classes and literals retain their characters`(regex: String, literal: String) {
        val spec = RegExSpec(regex, RegexMatchMode.WHOLE_VALUE)
        val generated = listOf(
            spec.generateShortestStringOrRandom(0),
            spec.generateLongestStringOrRandom(literal.length),
            spec.generateRandomString(literal.length, literal.length).toStringLiteral(),
        )

        assertThat(generated).containsOnly(literal)
        assertThat(spec.match(StringValue(literal))).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            ".; [A-Za-z0-9]",
            "\\S; [A-Za-z0-9]",
            "\\D; [A-Za-z]",
            "\\W; [!-/:-@\\[-\\^`{-~]",
            "\\s; '[ ]'",
        ],
    )
    fun `broad constructs generate preferred characters without restricting matching`(atom: String, preferred: String) {
        val spec = RegExSpec("^($atom){4}$")
        val generated = listOf(
            spec.generateShortestStringOrRandom(0),
            spec.generateLongestStringOrRandom(10),
        ) + List(20) { spec.generateRandomString(4, 4).toStringLiteral() }

        assertThat(generated).allSatisfy { value ->
            assertThat(value).matches("($preferred){4}")
            assertThat(spec.match(StringValue(value))).isTrue()
        }
        val nonPreferred = if (atom == "\\s") "\t" else "\u0001"
        assertThat(spec.match(StringValue(nonPreferred.repeat(4)))).isTrue()
    }

    companion object {
        @JvmStatic
        fun literalCases(): Stream<Arguments> = Stream.of(
            Arguments.of("""\.""", "."),
            Arguments.of("[.]", "."),
            Arguments.of("""\\S""", """\S"""),
            Arguments.of("[\u0001]{2}", "\u0001\u0001"),
            Arguments.of("""\Q.\E""", "."),
            Arguments.of("""\Q.\S[]\E""", """.\S[]"""),
            Arguments.of("""\Q^.\S$\E""", """^.\S$"""),
            Arguments.of("""\Q\b\E""", """\b"""),
            Arguments.of("""\\b""", """\b"""),
        )
    }
}
