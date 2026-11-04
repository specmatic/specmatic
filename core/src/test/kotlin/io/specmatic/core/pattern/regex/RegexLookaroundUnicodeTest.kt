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

internal class RegexLookaroundUnicodeTest {
    @Nested
    inner class FiniteLanguages {
        @ParameterizedTest
        @CsvSource("AHEAD, POSITIVE", "AHEAD, NEGATIVE", "BEHIND, POSITIVE", "BEHIND, NEGATIVE")
        fun `handles long conditional assertions without overlapping checks at their own marker`(direction: String, polarity: LookaroundPolarity) {
            val assertion = if (direction == "AHEAD") "(?" else "(?<"
            val check = assertion + (if (polarity == LookaroundPolarity.POSITIVE) "=" else "!") + "a{40}b)"
            val branch = if (direction == "AHEAD") check + "[ab]{41}" else "[ab]{41}" + check
            val word = Automaton.makeString("a".repeat(40) + "b")
            val expected = if (polarity == LookaroundPolarity.POSITIVE) word else RegExp("[ab]{41}", 0).toAutomaton().minus(word)
            assertThat(compile("^($branch|c)((?=z)z|y)$"))
                .isEqualTo(expected.union(Automaton.makeString("c")).concatenate(Automaton.makeCharSet("zy")))
        }

        @ParameterizedTest
        @CsvSource(delimiter = '→', value = [
            "^(?=\u00A0)\u00A0$→\u00A0", "^(?=\u0085)\u0085$→\u0085", "^(?=é)é$→é", "^(?=[aé])[aé]$→[aé]", "^(?!é)[aé]$→a",
            "^中(?<=中)文$→中文", "^[中Ж](?<!中)文$→Ж文",
            "^(?!中)[中Ж]$→Ж", "^(?=😀)😀$→😀", "^😀(?<=😀)é$→😀é",
            "^(é(?=a)a|中)$→éa|中", "^(é(?<=é)|中)$→é|中", "^(é(?<!é)|中)$→中",
            "^((?=é)é|中)((?<=é)[ab]|Ж)$→é[ab]|[é中]Ж",
            "^((?=é|中)[é中]|a)(?<=é)(?!中)[ab]$→é[ab]",
            "^((?!)é|中)$→中", "^((?=é)é|)$→é|()",
            "^((?=中)?é|中)$→é|中", "^((?=é){2,10}é|中)$→é|中",
            "^(?<name>é(?=a)a|中)$→éa|中",
        ])
        fun `compares complete finite languages for Unicode and conditional assertions`(pattern: String, expected: String) {
            assertThat(compile(pattern)).isEqualTo(RegExp(expected, 0).toAutomaton())
        }

        @Test
        fun `reserves only actual markers even when a source range includes them`() {
            val pattern = "^(?=[\uE000-\uE002])[\uE000-\uE002]$"
            assertThat(compile(pattern)).isEqualTo(Automaton.makeCharSet("\uE000\uE002"))
        }
    }

    @Nested
    inner class MarkerSafety {
        @ParameterizedTest
        @ValueSource(strings = ["^(a(?=x)x|..)$", "^(a(?!x)x|..)$", "^(a(?<=a)x|..)$", "^(a(?<!a)x|..)$"])
        fun `consuming wildcards cannot impersonate a marker from another alternative`(pattern: String) {
            val expected = RegExp(RegExSpec(null).cleanRegularRegexContent(".."), 0).toAutomaton()
                .intersection(RegexCandidateAlphabet(emptyList()).strings)
            assertThat(compile(pattern)).isEqualTo(expected)
        }

        @ParameterizedTest
        @ValueSource(strings = ["^(a(?=x)x|..)((?=z)z|y)$", "^(a(?!x)x|..)((?=z)z|y)$", "^(a(?<=a)x|..)((?=z)z|y)$", "^(a(?<!a)x|..)((?=z)z|y)$"])
        fun `protects wildcard consumption when independent assertions keep alternatives in one automaton`(pattern: String) {
            val expected = RegExp(RegExSpec(null).cleanRegularRegexContent("..[zy]"), 0).toAutomaton()
                .intersection(alphabet(pattern).strings)
            assertThat(compile(pattern)).isEqualTo(expected)
        }

        @ParameterizedTest
        @ValueSource(strings = ["^.(?=b)b$", "^[^a](?=b)b$", "^[\u0000-\uFFFF](?=b)b$"])
        fun `keeps required consumption when broad classes can otherwise consume markers`(pattern: String) {
            val automaton = compile(pattern)
            assertThat(automaton.run("b")).isFalse()
            assertThat(automaton.run("éb")).isTrue()
            assertThat(automaton.run("\uE010b")).isTrue()
            assertThat(automaton.run("\uE000b")).isFalse()
        }

    }

    @Nested
    inner class DeferredCleanupInaccuracies {
        @ParameterizedTest
        @CsvSource(delimiter = '→', value = [
            "^(?!\\s).$→\u00A0", "^(?=[^\\s]).$→\u00A0", "^(?!.)[^a]$→\u0085",
        ])
        fun `retains inherited cleanup mismatches without excluding candidate characters`(pattern: String, candidate: String) {
            // Characterizes existing cleanup; these witnesses are invalid in ECMAScript.
            assertThat(compile(pattern).run(candidate)).isTrue()
        }
    }

    @Nested
    inner class ContextAndScalability {
        @ParameterizedTest
        @CsvSource("SEARCH, 64", "WHOLE_VALUE, 64", "SEARCH, 128", "WHOLE_VALUE, 128")
        fun `unions mixed anchors without retaining exponential search history subsets`(mode: RegexMatchMode, routes: Int) {
            val words = (0 until routes).map { "a" + it.toString().padStart(3, '0') }
            val source = words.mapIndexed { index, word ->
                val start = if (index % 4 in listOf(0, 1)) "^" else ""
                val end = if (index % 4 in listOf(0, 2)) "$" else ""
                "$start(?=a)$word$end"
            }.joinToString("|")
            val alternatives = (0..3).map { kind -> words.filterIndexed { index, _ -> index % 4 == kind }.joinToString("|") }
            val context = alphabet("(?=a)").contextRegex
            val expected = if (mode == RegexMatchMode.WHOLE_VALUE) words.joinToString("|") else
                "(${alternatives[0]})|(${alternatives[1]})$context|$context(${alternatives[2]})|$context(${alternatives[3]})$context"
            assertThat(compile(source, mode)).isEqualTo(RegExp(expected, 0).toAutomaton())
        }

        @ParameterizedTest
        @CsvSource(delimiter = '→', value = [
            "SEARCH→(?=é)é→\u00A0é\u0085→true", "SEARCH→(?<=中)é→中é→true", "WHOLE_VALUE→(?<=中)é→中é→false",
            "SEARCH→^(?=é)é→é中→true", "WHOLE_VALUE→^(?=é)é→é中→false",
            "SEARCH→é(?<=é)$→中é→true", "WHOLE_VALUE→é(?<=é)$→中é→false",
            "SEARCH→^(?=é)é$|中→x中x→true", "SEARCH→^(?=é)é$|^中$→x中x→false",
        ])
        fun `preserves Unicode context and independently normalized anchors`(mode: RegexMatchMode, pattern: String, candidate: String, expected: Boolean) {
            assertThat(compile(pattern, mode).run(candidate)).isEqualTo(expected)
        }

        @Test
        fun `compiles unanchored root alternatives without a route cutoff`() {
            val pattern = List(128) { "(?=é)é" }.joinToString("|")
            assertThat(compile(pattern, RegexMatchMode.WHOLE_VALUE)).isEqualTo(Automaton.makeString("é"))
        }

        @ParameterizedTest
        @ValueSource(ints = [7, 8, 10, 12])
        fun `retains independent alternatives without enumerating their Cartesian product`(groups: Int) {
            val pattern = "^" + "((?=é)é|中)".repeat(groups) + "$"
            val automaton = compile(pattern)
            assertThat(automaton).isEqualTo(RegExp("[é中]{$groups}", 0).toAutomaton())
            assertThat(automaton.numberOfStates).isEqualTo(groups + 1)
        }
    }

    @Nested
    inner class BoundedOracle {
        @ParameterizedTest
        @CsvSource(delimiter = '→', value = [
            "^(é(?=a)a|中)$", "^((?=é)é|中)((?=a)a|b)$",
            "^((?!中)é|中)((?<=é)a|b)$", "^((?=é|中)[é中]|a)(?<!中)[ab]$",
            "^a((?=b)b|中)(?<=ab)[é中]$", "(?<=中)(é|a(?=b)b)",
        ])
        fun `matches the independent JVM oracle for every short candidate in both modes`(pattern: String) {
            for (mode in RegexMatchMode.entries) {
                val automaton = compile(pattern, mode)
                // SEARCH uses strict end assertions, as RegExSpec does; JVM $ otherwise accepts a final newline.
                val oracle = Regex(if (mode == RegexMatchMode.SEARCH) pattern.replace("$", "\\z") else pattern)
                val candidates = boundedCandidates()
                val expected = candidates.filter { if (mode == RegexMatchMode.SEARCH) oracle.containsMatchIn(it) else oracle.matches(it) }
                assertThat(candidates.filter(automaton::run)).containsExactlyElementsOf(expected)
            }
        }
    }

    private fun boundedCandidates(): List<String> {
        val letters = listOf("a", "b", "é", "中", "Ж", "\uE010", "\n")
        var level = listOf("")
        return buildList {
            add("")
            repeat(3) {
                level = level.flatMap { prefix -> letters.map { prefix + it } }
                addAll(level)
            }
        }
    }

    private fun alphabet(pattern: String): RegexCandidateAlphabet {
        val scan = RegexScanner().scan(pattern) as RegexScanner.Result.Scanned
        val extracted = RegexLookaroundExtractor().extract(pattern, scan) as RegexLookaroundExtraction.Extracted
        return RegexCandidateAlphabet(extracted.assertions)
    }

    private fun compile(pattern: String, mode: RegexMatchMode = RegexMatchMode.SEARCH): Automaton {
        val result = RegexLookaroundCompiler(mode, RegExSpec(null)::cleanRegularRegexContent).compile(pattern)
        assertThat(result).describedAs(pattern).isInstanceOf(RegexLookaroundCompilation.Compiled::class.java)
        return (result as RegexLookaroundCompilation.Compiled).automaton
    }
}
