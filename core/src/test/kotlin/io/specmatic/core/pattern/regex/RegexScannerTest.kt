package io.specmatic.core.pattern.regex

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

internal class RegexScannerTest {
    @Nested
    inner class Tokenization {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.pattern.regex.RegexScannerTest#assertionOpenings")
        fun `preserves the type polarity and span of each assertion`(source: String, opening: RegexToken.GroupStart.Lookaround) {
            val bodyStart = opening.span.end
            val closing = RegexToken.GroupEnd(RegexSpan(bodyStart + 1, bodyStart + 2))
            assertThat(RegexScanner().scan(source)).isEqualTo(
                RegexScanner.Result.Scanned(
                    tokens = listOf(opening, RegexToken.Character(RegexSpan(bodyStart, bodyStart + 1)), closing),
                    groups = listOf(RegexGroup.Lookaround(opening, closing)),
                ),
            )
        }

        @Test
        fun `preserves the complete token sequence including class escapes and lazy quantifiers`() {
            val source = """^a\d[\]]+?|b{1,2}$"""
            assertThat(scanned(source).tokens).containsExactly(
                RegexToken.Anchor(RegexSpan(0, 1)),
                RegexToken.Character(RegexSpan(1, 2)),
                RegexToken.Escape(RegexSpan(2, 4)),
                RegexToken.CharacterClass(RegexSpan(4, 8), listOf(RegexToken.Escape(RegexSpan(5, 7)))),
                RegexToken.Quantifier(RegexSpan(8, 10)),
                RegexToken.Alternation(RegexSpan(10, 11)),
                RegexToken.Character(RegexSpan(11, 12)),
                RegexToken.Quantifier(RegexSpan(12, 17)),
                RegexToken.Anchor(RegexSpan(17, 18)),
            )
        }

        @Test
        fun `keeps assertion text inside an escaped character class literal`() {
            val source = """[\](?=)](?=a)a"""
            assertThat(scanned(source).tokens).containsExactly(
                RegexToken.CharacterClass(RegexSpan(0, 8), listOf(RegexToken.Escape(RegexSpan(1, 3)))),
                RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(8, 11), LookaroundPolarity.POSITIVE),
                RegexToken.Character(RegexSpan(11, 12)),
                RegexToken.GroupEnd(RegexSpan(12, 13)),
                RegexToken.Character(RegexSpan(13, 14)),
            )
        }

        @Test
        fun `distinguishes escaped parentheses and class parentheses from assertion structure`() {
            val source = "\\((?=a[()])a\\)"
            val opening = RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(2, 5), LookaroundPolarity.POSITIVE)
            val closing = RegexToken.GroupEnd(RegexSpan(10, 11))
            assertThat(RegexScanner().scan(source)).isEqualTo(
                RegexScanner.Result.Scanned(
                    tokens = listOf(
                        RegexToken.Escape(RegexSpan(0, 2)),
                        opening,
                        RegexToken.Character(RegexSpan(5, 6)),
                        RegexToken.CharacterClass(RegexSpan(6, 10), emptyList()),
                        closing,
                        RegexToken.Character(RegexSpan(11, 12)),
                        RegexToken.Escape(RegexSpan(12, 14)),
                    ),
                    groups = listOf(RegexGroup.Lookaround(opening, closing)),
                ),
            )
        }

        @ParameterizedTest
        @CsvSource(delimiter = '→', value = ["\\Q(?=a)\\E→9", "\\Q(?=a)→7"])
        fun `keeps JVM quoted text as one literal token`(source: String, end: Int) {
            assertThat(RegexScanner().scan(source)).isEqualTo(
                RegexScanner.Result.Scanned(listOf(RegexToken.QuotedLiteral(RegexSpan(0, end))), emptyList()),
            )
        }

        @Test
        fun `scans an empty source without tokens or groups`() {
            assertThat(RegexScanner().scan("")).isEqualTo(RegexScanner.Result.Scanned(emptyList(), emptyList()))
        }
    }

    @Nested
    inner class GroupStructure {
        @Test
        fun `pairs nested groups and identifies their enclosing source spans`() {
            val source = "(a(?=b)b)"
            val inner = RegexGroup.Lookaround(
                RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(2, 5), LookaroundPolarity.POSITIVE),
                RegexToken.GroupEnd(RegexSpan(6, 7)),
            )
            val outer = RegexGroup.Regular(RegexToken.GroupStart.Regular(RegexSpan(0, 1)), RegexToken.GroupEnd(RegexSpan(8, 9)))
            val result = scanned(source)

            assertThat(result.groups).containsExactly(inner, outer)
            assertThat(inner.body.text(source)).isEqualTo("b")
            assertThat(inner.span.text(source)).isEqualTo("(?=b)")
            assertThat(result.enclosingGroups(inner.span)).containsExactly(outer)
            assertThat(result.innermostGroup(inner.body)).isEqualTo(inner)
            assertThat(result.innermostGroup(RegexSpan(0, 1))).isNull()
        }

        @Test
        fun `distinguishes noncapturing and special group openings`() {
            assertThat(scanned("(?:a)").groups).containsExactly(
                RegexGroup.Regular(RegexToken.GroupStart.Regular(RegexSpan(0, 3)), RegexToken.GroupEnd(RegexSpan(4, 5))),
            )
            assertThat(scanned("(?i)").groups).containsExactly(
                RegexGroup.Regular(RegexToken.GroupStart.Special(RegexSpan(0, 2)), RegexToken.GroupEnd(RegexSpan(3, 4))),
            )
            assertThat(scanned("(?<name>a)").groups).containsExactly(
                RegexGroup.Regular(RegexToken.GroupStart.Named(RegexSpan(0, 8), "name"), RegexToken.GroupEnd(RegexSpan(9, 10))),
            )
        }
    }

    @Nested
    inner class MalformedInput {
        @ParameterizedTest
        @CsvSource(delimiter = '→', value = [")(?=a)a→Unmatched closing group", "(?=a→Unclosed group"])
        fun `reports unmatched groups without losing the lexical source`(source: String, reason: String) {
            val result = RegexScanner().scan(source)
            assertThat(result).isInstanceOf(RegexScanner.Result.Malformed::class.java)
            result as RegexScanner.Result.Malformed
            assertThat(result.reason).isEqualTo(reason)
            assertThat(result.tokens.joinToString("") { it.text(source) }).isEqualTo(source)
            assertThat(result.tokens.filterIsInstance<RegexToken.GroupStart.Lookaround>()).hasSize(1)
        }

        @Test
        fun `leaves incomplete escapes and classes for regular compilation`() {
            assertThat(RegexScanner().scan("\\")).isEqualTo(
                RegexScanner.Result.Scanned(listOf(RegexToken.Escape(RegexSpan(0, 1))), emptyList()),
            )
            assertThat(RegexScanner().scan("[\\")).isEqualTo(
                RegexScanner.Result.Scanned(
                    listOf(RegexToken.CharacterClass(RegexSpan(0, 2), listOf(RegexToken.Escape(RegexSpan(1, 2))))),
                    emptyList(),
                ),
            )
        }
    }

    private fun scanned(source: String): RegexScanner.Result.Scanned {
        val result = RegexScanner().scan(source)
        assertThat(result).isInstanceOf(RegexScanner.Result.Scanned::class.java)
        return result as RegexScanner.Result.Scanned
    }

    companion object {
        @JvmStatic
        fun assertionOpenings() = listOf(
            Arguments.of("(?=a)", RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(0, 3), LookaroundPolarity.POSITIVE)),
            Arguments.of("(?!a)", RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(0, 3), LookaroundPolarity.NEGATIVE)),
            Arguments.of("(?<=a)", RegexToken.GroupStart.Lookaround.Behind(RegexSpan(0, 4), LookaroundPolarity.POSITIVE)),
            Arguments.of("(?<!a)", RegexToken.GroupStart.Lookaround.Behind(RegexSpan(0, 4), LookaroundPolarity.NEGATIVE)),
        )
    }
}
