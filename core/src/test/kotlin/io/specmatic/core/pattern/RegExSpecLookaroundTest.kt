package io.specmatic.core.pattern

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import io.specmatic.core.pattern.regex.RegexBasedStringGenerator
import io.specmatic.core.pattern.regex.RegexLookaroundCompilation
import io.specmatic.core.pattern.regex.RegexLookaroundCompiler
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class RegExSpecLookaroundTest {
    @Nested
    inner class GenerationApis {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '→', value = [
            "^a(?=b)b$→ab",
            "^a(?!b)c$→ac",
            "^ab(?<=ab)c$→abc",
            "^a(?<!b)c$→ac",
        ])
        fun `uses each assertion type for shortest longest and random generation`(pattern: String, expected: String) {
            val spec = RegExSpec(pattern)
            assertThat(spec.generateShortestStringOrRandom(0)).isEqualTo(expected)
            assertThat(spec.generateLongestStringOrRandom(20)).isEqualTo(expected)
            assertThat(spec.generateRandomString(expected.length, expected.length).toStringLiteral()).isEqualTo(expected)
            assertThat(spec.match(StringValue(expected))).isTrue()
        }

        @Test
        fun `generates client passwords with all required character categories`() {
            val pattern = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).*$"
            val spec = RegExSpec(pattern)
            val shortest = spec.generateShortestStringOrRandom(0)
            val longest = spec.generateLongestStringOrRandom(30)
            assertThat(Regex(pattern).matches(shortest)).isTrue()
            assertThat(longest).hasSize(30)
            assertThat(Regex(pattern).matches(longest)).isTrue()

            repeat(20) {
                val generated = spec.generateRandomString(8, 30).toStringLiteral()
                assertThat(generated.length).isBetween(8, 30)
                assertThat(Regex(pattern).matches(generated)).isTrue()
            }

            assertThat(spec.match(StringValue("Ab1!é"))).isTrue()
            assertThat(spec.match(StringValue("Abcd"))).isFalse()
        }
    }

    @Nested
    inner class ExistingGenerationBehavior {
        @Test
        fun `returns the existing empty fallback when no witness fits the requested bounds`() {
            val impossible = "^a(?!b)b$"
            val automaton = compile(impossible)
            assertThat(automaton.isEmpty).isTrue()

            val generator = RegexBasedStringGenerator(impossible, automaton)
            assertThat(generator.random(0, 10)).isEmpty()
            assertThat(RegExSpec(impossible).generateRandomString(0, 10).toStringLiteral()).isEmpty()

            val nonempty = RegexBasedStringGenerator("^(?=ab)ab$", compile("^(?=ab)ab$"))
            assertThat(nonempty.random(0, 1)).isEmpty()
            assertThat(RegExSpec("^(?=)$").generateRandomString(0, 0).toStringLiteral()).isEmpty()
        }

        @Test
        fun `falls back to legacy generation without changing runtime matching for unsupported lookarounds`() {
            val pattern = "^a(?=b(?<=ab))b$"
            val legacyRegex = "a(?=b(?<=ab))b"
            assertThat(compiler().compile(pattern)).isInstanceOf(RegexLookaroundCompilation.Unsupported::class.java)

            val spec = RegExSpec(pattern)
            val legacyGenerator = RegexBasedStringGenerator(legacyRegex)
            assertThat(spec.toString()).isEqualTo(legacyRegex)
            assertThat(spec.match(StringValue("ab"))).isTrue()
            assertThat(spec.match(StringValue("ac"))).isFalse()
            assertThat(spec.generateShortestStringOrRandom(0)).isEqualTo(legacyGenerator.generateShortest())
            assertThat(spec.generateLongestStringOrRandom(30)).isEqualTo(legacyGenerator.generateLongest(30))

            val generated = spec.generateRandomString(0, 30).toStringLiteral()
            assertThat(RegExp(legacyRegex, 0).toAutomaton().run(generated)).isTrue()
        }

        @Test
        fun `inherits cleanup inaccuracies without rejecting lookaround expressions`() {
            val pattern = "^(?![\\D_])_$"
            assertThat(compile(pattern).run("_")).isTrue()

            val spec = RegExSpec(pattern)
            assertThat(spec.generateShortestStringOrRandom(0)).isEqualTo("_")
            assertThat(spec.match(StringValue("_"))).isFalse()

            // Existing clean-up reads \t as t; the lookaround path inherits the same behaviour.
            assertThat(compile("^(?=\\t)\\t$").run("t")).isTrue()
            assertThat(RegExSpec("^\\t$").generateShortestStringOrRandom(0)).isEqualTo("t")
        }

        @Test
        fun `preserves ordinary regex cleanup and generation fallback`() {
            assertThat(compiler().compile("\\Q(?=a)\\E")).isEqualTo(RegexLookaroundCompilation.NotApplicable)
            assertThat(compiler().compile("[(?=)]")).isEqualTo(RegexLookaroundCompilation.NotApplicable)
            assertThat(compiler().compile("\\(\\?=a\\)")).isEqualTo(RegexLookaroundCompilation.NotApplicable)
            assertThat(RegExSpec("^\\t$").generateShortestStringOrRandom(0)).isEqualTo("t")
            assertThat(RegexBasedStringGenerator("a").random(2, 2)).isEmpty()
        }
    }

    private fun compiler() = RegexLookaroundCompiler(RegexMatchMode.SEARCH, RegExSpec(null)::cleanRegularRegexContent)

    private fun compile(pattern: String): Automaton {
        val result = compiler().compile(pattern)
        assertThat(result).isInstanceOf(RegexLookaroundCompilation.Compiled::class.java)
        return (result as RegexLookaroundCompilation.Compiled).automaton
    }
}
