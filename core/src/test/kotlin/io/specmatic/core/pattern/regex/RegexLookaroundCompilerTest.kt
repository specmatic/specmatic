package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import io.specmatic.core.pattern.RegExSpec
import io.specmatic.core.pattern.RegexMatchMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class RegexLookaroundCompilerTest {
    @Nested
    inner class AssertionLanguages {
        @ParameterizedTest(name = "{0}")
        @CsvSource(
            delimiter = '→',
            value = [
                "^(?=a)[ab]$→a",
                "^(?!a)[ab]$→b",
                "^a(?<=a)[bc]$→a[bc]",
                "^[ab](?<!a)c$→bc",
                "^(?=(?:ab|ac)+)(ab|ac){1,2}$→(ab|ac){1,2}",
                "^(?!a(b|c)+)[abc]{3}$→aa[abc]|b[abc]{2}|c[abc]{2}",
                "^(?=)a$→a",
                "^a(?<=)b$→ab",
                "^(?=a)(?<name>a)$→a",
                "^(?=(?<body>ab))(?<value>ab)$→ab",
                "^(?!(?<bad>a))[ab]$→b",
                "^(?<outer>a(?=b)b)$→ab",
                "^(?=a+?)a+?$→a+",
                "^(?!a+?)[ab]+?$→b[ab]*",
                "^(?=a??)a??$→a?",
                "^(?=a{2,4}?)a{2,4}?$→a{2,4}",
            ],
        )
        fun `compiles the complete expected language for basic assertions and regular bodies`(pattern: String, expected: String) {
            assertThat(compile(pattern)).isEqualTo(RegExp(expected, 0).toAutomaton())
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '→', value = [
            "^a(?=b)..$→abb→true",
            "^a(?=b)..$→acb→false",
            "^a(?!b)..$→acb→true",
            "^a(?!b)..$→abb→false",
            "^..(?<=b)c$→abc→true",
            "^..(?<=b)c$→bac→false",
            "^..(?<!b)c$→bac→true",
            "^..(?<!b)c$→abc→false",
        ])
        fun `evaluates bodies adjacent to the assertion position`(pattern: String, candidate: String, expected: Boolean) {
            assertThat(compile(pattern).run(candidate)).isEqualTo(expected)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["^(?=a).$", "^(?!a).$", "^.(?<=a)$", "^.(?<!a)$"])
        fun `preserves dot semantics for every ASCII character`(pattern: String) {
            val automaton = compile(pattern)
            val candidates = (0..127).map { it.toChar().toString() }
            val expected = candidates.filter { Regex(pattern).matches(it) }
            assertThat(candidates.filter(automaton::run)).containsExactlyElementsOf(expected)
        }
    }

    @Nested
    inner class IndependentAssertionComposition {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '→', value = [
            "^(?=[ab])(?=a)[ab]$→a",
            "^a(?=bc)b(?=c)c$→abc",
            "^a(?<=a)b(?<=ab)c$→abc",
            "^a(?=bc)b(?<=ab)(?!d)c$→abc",
            "^((a|b)(?=c)c)$→(a|b)c",
            "^(?=a){1}a$→a",
            "^(?=a){1,1}a$→a",
            "^(?!b){1}?a$→a",
            "^((?=a)a){1,1}?$→a",
        ])
        fun `composes assertions across markers and erases all marker characters`(pattern: String, expected: String) {
            assertThat(compile(pattern)).isEqualTo(RegExp(expected, 0).toAutomaton())
        }
    }

    @Nested
    inner class MatchingModes {
        @ParameterizedTest(name = "{0}: {1} against {2}")
        @CsvSource(delimiter = '→', value = [
            "SEARCH→(?<=a)b→ab→true",
            "WHOLE_VALUE→(?<=a)b→ab→false",
            "WHOLE_VALUE→a(?<=a)b→ab→true",
            "SEARCH→(?=a)a→ba→true",
            "WHOLE_VALUE→(?=a)a→ba→false",
            "SEARCH→^(?=a)a→ab→true",
            "WHOLE_VALUE→^(?=a)a→ab→false",
            "SEARCH→a(?<=a)$→ba→true",
            "WHOLE_VALUE→a(?<=a)$→ba→false",
            "SEARCH→^(?=a)a$→ba→false",
        ])
        fun `adds search context only where permitted by the matching mode and anchors`(
            mode: RegexMatchMode, pattern: String, candidate: String, expected: Boolean,
        ) {
            assertThat(compile(pattern, mode).run(candidate)).isEqualTo(expected)
        }

        @Test
        fun `includes literal newlines in lookbehind search context`() {
            val automaton = compile("(?<=\n)c$")
            assertThat(automaton.run("\nc")).isTrue()
            assertThat(automaton.run("c")).isFalse()
        }
    }

    @Nested
    inner class CompilationOutcomes {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["a", "\\Q(?=a)\\E", "[(?=)]", "\\(\\?=a\\)", ")a"])
        fun `leaves ordinary and literal assertion expressions to the existing pipeline`(pattern: String) {
            assertThat(compiler().compile(pattern)).isEqualTo(RegexLookaroundCompilation.NotApplicable)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = [
            "^a(?!b)b$", "^a(?!bc)b(?=c)c$", "^a(?<!a)b$", "^(?!)a$", "^a(?<!)b$", "^(?=é)é$",
        ])
        fun `returns a compiled empty automaton for contradictions and non ASCII only languages`(pattern: String) {
            assertThat(compile(pattern).isEmpty).isTrue()
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '→', value = [
            ")(?=a)a→Unmatched closing group",
            "(?=a→Unclosed group",
            "^(?=a)[]$→expected ']' at position 3",
            "(?=a)[\\→expected ']' at position 4",
            "(?=(?!a)b)b→Nested lookarounds are unsupported",
        ])
        fun `returns explicit unsupported results for malformed or out of scope compilation`(pattern: String, reason: String) {
            assertThat(compiler().compile(pattern)).isEqualTo(RegexLookaroundCompilation.Unsupported(reason))
        }

        @Test
        fun `restricts the generation domain without rejecting non ASCII source syntax`() {
            assertThat(compile("^(?=[aé])[aé]$")).isEqualTo(Automaton.makeString("a"))
            assertThat(compile("^(?!é)[aé]$")).isEqualTo(Automaton.makeString("a"))
        }
    }

    private fun compiler(mode: RegexMatchMode = RegexMatchMode.SEARCH) = RegexLookaroundCompiler(mode, RegExSpec(null)::cleanRegularRegexContent)

    private fun compile(pattern: String, mode: RegexMatchMode = RegexMatchMode.SEARCH): Automaton {
        val result = compiler(mode).compile(pattern)
        assertThat(result).isInstanceOf(RegexLookaroundCompilation.Compiled::class.java)
        return (result as RegexLookaroundCompilation.Compiled).automaton
    }
}
