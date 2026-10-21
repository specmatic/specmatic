package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import io.specmatic.core.pattern.RegExSpec
import io.specmatic.core.pattern.RegexMatchMode
import io.specmatic.core.utilities.Flags
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
    inner class NamedCaptures {
        @ParameterizedTest
        @ValueSource(strings = ["user_id", "_id", "\$id", "é"])
        fun `compiles ECMAScript capture names that the JVM runtime dialect rejects`(name: String) {
            assertThat(compile("^(?=a)(?<$name>a)$")).isEqualTo(Automaton.makeString("a"))
        }

        @ParameterizedTest
        @ValueSource(strings = ["user_id", "_id", "\$id", "é"])
        fun `still declines named backreferences for every recognized capture name`(name: String) {
            assertThat(compiler().compile("^(?=(?<$name>a))\\k<$name>$")).isEqualTo(
                RegexLookaroundCompilation.Unsupported("Backreferences are outside lookaround generation support"),
            )
        }
    }

    @Nested
    inner class CleanupOrdering {
        @Test
        fun `cleans main and assertion content once without interpreting capture names as anchors`() {
            val inputs = mutableListOf<String>()
            val compiler = RegexLookaroundCompiler(RegexMatchMode.SEARCH) { expression ->
                inputs += expression
                RegExSpec(null).cleanRegularRegexContent(expression)
            }
            val result = compiler.compile("^(?=a)(?<\$id>a)$")
            assertThat(result).isInstanceOf(RegexLookaroundCompilation.Compiled::class.java)
            assertThat((result as RegexLookaroundCompilation.Compiled).automaton).isEqualTo(Automaton.makeString("a"))
            assertThat(inputs).containsExactly("^\uE000(?<\$id>a)$", "a")
        }

        @Test
        fun `preserves explicit unsupported results when regular content cleanup fails`() {
            val compiler = RegexLookaroundCompiler(RegexMatchMode.SEARCH) { throw IllegalArgumentException("Unsupported regular content") }
            assertThat(compiler.compile("(?=a)a")).isEqualTo(RegexLookaroundCompilation.Unsupported("Unsupported regular content"))
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
            "^(?=ab){2,10}ab$→ab",
            "^(?!ab){2,10}[ab]{2}$→aa|ba|bb",
            "^a(?=b)?c$→ac",
            "^a(?!c)*c$→ac",
            "^(?=z){0,2}a$→a",
            "^(?=z){0}a$→a",
            "^a(?=z)*b(?=c)c$→abc",
        ])
        fun `composes assertions across markers and erases all marker characters`(pattern: String, expected: String) {
            assertThat(compile(pattern)).isEqualTo(RegExp(expected, 0).toAutomaton())
        }
    }

    @Nested
    inner class AlternativeBranches {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '→', value = [
            "^a(?!b)b$|^ab$→ab",
            "^(?=a)a$|^(?=b)b$→a|b",
            "^(a(?=b)b|c)$→ab|c",
            "^x(a(?=b)b|c)y$→xaby|xcy",
            "^(?<value>a(?=b)b|c)$→ab|c",
            "^(?<\$id>a(?=b)b|c)$→ab|c",
            "^((?=a)a|b)((?=c)c|d)$→ac|ad|bc|bd",
            "^a((?=b)b|c)(?<=ab)d$→abd",
            "^((?=a)a|((?=b)b|c))$→a|b|c",
            "^(?=a)a$|^$→a|()",
        ])
        fun `unions complete branch languages without leaking assertion constraints`(pattern: String, expected: String) {
            assertThat(compile(pattern)).isEqualTo(RegExp(expected, 0).toAutomaton())
        }

        @ParameterizedTest
        @CsvSource(delimiter = '→', value = [
            "SEARCH→(?=a)a|b→xbx→true",
            "WHOLE_VALUE→(?=a)a|b→xbx→false",
            "SEARCH→^a(?!b)b$|ab→xabx→true",
            "SEARCH→^a(?!b)b$|^ab$→xabx→false",
        ])
        fun `preserves each branch search and anchor semantics`(mode: RegexMatchMode, pattern: String, candidate: String, expected: Boolean) {
            assertThat(compile(pattern, mode).run(candidate)).isEqualTo(expected)
        }

        @Test
        fun `declines the complete expression when any branch needs unsupported assertion semantics`() {
            assertThat(compiler().compile("^(?=a)a$|^((?=b)b)+$")).isEqualTo(
                RegexLookaroundCompilation.Unsupported("Assertions inside optional or repeated groups are unsupported"),
            )
        }
    }

    @Nested
    inner class BranchBudget {
        @ParameterizedTest
        @CsvSource("60, false", "60, true", "63, false", "63, true")
        fun `shares the total budget across uneven branches regardless of their order`(nestedRoutes: Int, reverse: Boolean) {
            val alternatives = (0 until nestedRoutes).map { "a$it" }
            val nested = "^((?=a)" + alternatives.joinToString("|") + ")$"
            val pattern = if (reverse) "$nested|^z$" else "^z$|$nested"
            val expected = Automaton.union((alternatives + "z").map(Automaton::makeString))

            assertThat(compile(pattern)).isEqualTo(expected)
        }

        @Test
        fun `declines the whole expression when uneven branches require 65 final routes`() {
            val alternatives = (0 until 64).joinToString("|") { "a$it" }
            assertThat(compiler().compile("^z$|^((?=a)$alternatives)$")).isEqualTo(
                RegexLookaroundCompilation.Unsupported(
                    "Lookaround alternative expansion exceeds the branch budget of 64;\n" +
                        "increase SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to allow more branches",
                ),
            )
        }

        @Test
        fun `bounds products of independent assertion alternatives by default`() {
            val group = "((?=a)a|b)"
            val supported = "^" + group.repeat(6) + "$"
            val excessive = "^" + group.repeat(7) + "$"
            assertThat(compile(supported)).isEqualTo(RegExp("[ab]{6}", 0).toAutomaton())
            assertThat(compiler().compile(excessive)).isEqualTo(
                RegexLookaroundCompilation.Unsupported(
                    "Lookaround alternative expansion exceeds the branch budget of 64;\n" +
                        "increase SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to allow more branches",
                ),
            )
        }

        @ParameterizedTest
        @CsvSource("2, 1", "128, 7")
        fun `uses the configured budget to compile complete branch languages`(budget: String, groups: Int) {
            Flags.using(Flags.SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to budget) {
                val pattern = "^" + "((?=a)a|b)".repeat(groups) + "$"
                assertThat(compile(pattern)).isEqualTo(RegExp("[ab]{$groups}", 0).toAutomaton())
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["^(?=a)a$|^b$", "^(?!)a$|^b$"])
        fun `counts every final route including empty languages against the configured total`(pattern: String) {
            Flags.using(Flags.SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to "1") {
                assertThat(compiler().compile(pattern)).isEqualTo(
                    RegexLookaroundCompilation.Unsupported(
                        "Lookaround alternative expansion exceeds the branch budget of 1;\n" +
                            "increase SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to allow more branches",
                    ),
                )
            }
        }

        @Test
        fun `starts a fresh budget when reusing a compiler after exhaustion`() {
            Flags.using(Flags.SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to "1") {
                val compiler = compiler()
                val expected = RegexLookaroundCompilation.Compiled(Automaton.makeString("a"))
                assertThat(compiler.compile("^(?=a)a$")).isEqualTo(expected)
                assertThat(compiler.compile("^(?=a)a$|^b$")).isEqualTo(
                    RegexLookaroundCompilation.Unsupported(
                        "Lookaround alternative expansion exceeds the branch budget of 1;\n" +
                            "increase SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to allow more branches",
                    ),
                )
                assertThat(compiler.compile("^(?=a)a$")).isEqualTo(expected)
            }
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "invalid", "0", "-1", "2147483648"])
        fun `retains the default budget for invalid settings`(setting: String) {
            Flags.using(Flags.SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to setting) {
                val group = "((?=a)a|b)"
                assertThat(compile("^" + group.repeat(6) + "$")).isEqualTo(RegExp("[ab]{6}", 0).toAutomaton())
                assertThat(compiler().compile("^" + group.repeat(7) + "$")).isEqualTo(
                    RegexLookaroundCompilation.Unsupported(
                        "Lookaround alternative expansion exceeds the branch budget of 64;\n" +
                            "increase SPECMATIC_REGEX_LOOKAROUND_BRANCH_BUDGET to allow more branches",
                    ),
                )
            }
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
