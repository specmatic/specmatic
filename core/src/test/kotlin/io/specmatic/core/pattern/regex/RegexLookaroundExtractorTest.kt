package io.specmatic.core.pattern.regex

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class RegexLookaroundExtractorTest {
    @Nested
    inner class AssertionExtraction {
        @Test
        fun `replaces all four assertion types in source order`() {
            val source = "a(?=b)b(?!c)(?<=ab)(?<!x)"
            val expected = RegexLookaroundExtraction.Extracted(
                expression = "a\uE000b\uE001\uE002\uE003",
                assertions = listOf(
                    MarkedAssertion('\uE000', "b", RegexSpan(1, 6), RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(1, 4), LookaroundPolarity.POSITIVE)),
                    MarkedAssertion('\uE001', "c", RegexSpan(7, 12), RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(7, 10), LookaroundPolarity.NEGATIVE)),
                    MarkedAssertion('\uE002', "ab", RegexSpan(12, 19), RegexToken.GroupStart.Lookaround.Behind(RegexSpan(12, 16), LookaroundPolarity.POSITIVE)),
                    MarkedAssertion('\uE003', "x", RegexSpan(19, 25), RegexToken.GroupStart.Lookaround.Behind(RegexSpan(19, 23), LookaroundPolarity.NEGATIVE)),
                ),
            )
            // Deliberately reverse the input groups: replacement order must follow source spans.
            val groups = scanned(source).groups.reversed()
            assertThat(RegexLookaroundExtractor().extract(source, groups)).isEqualTo(expected)
        }

        @Test
        fun `preserves ordinary groups alternatives and quantifiers in assertion bodies`() {
            val source = "^((?=(a|b)+)[ab]+)(?<!c)$"
            assertThat(extract(source)).isEqualTo(
                RegexLookaroundExtraction.Extracted(
                    expression = "^(\uE000[ab]+)\uE001$",
                    assertions = listOf(
                        MarkedAssertion('\uE000', "(a|b)+", RegexSpan(2, 12), RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(2, 5), LookaroundPolarity.POSITIVE)),
                        MarkedAssertion('\uE001', "c", RegexSpan(18, 24), RegexToken.GroupStart.Lookaround.Behind(RegexSpan(18, 22), LookaroundPolarity.NEGATIVE)),
                    ),
                ),
            )
        }

        @Test
        fun `leaves a source without assertions unchanged`() {
            assertThat(extract("(a|b)+")).isEqualTo(RegexLookaroundExtraction.Extracted("(a|b)+", emptyList()))
        }

        @Test
        fun `extracts assertions independently of placement eligibility`() {
            val source = "^((?=a)a)?$"
            assertThat(extract(source)).isEqualTo(
                RegexLookaroundExtraction.Extracted(
                    "^(\uE000a)?$",
                    listOf(MarkedAssertion('\uE000', "a", RegexSpan(2, 7), RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(2, 5), LookaroundPolarity.POSITIVE))),
                ),
            )
        }
    }

    @Nested
    inner class MarkerAllocation {
        @Test
        fun `avoids markers that already occur in the source`() {
            val source = "\uE000(?=a)a"
            assertThat(extract(source)).isEqualTo(
                RegexLookaroundExtraction.Extracted(
                    "\uE000\uE001a",
                    listOf(MarkedAssertion('\uE001', "a", RegexSpan(1, 6), RegexToken.GroupStart.Lookaround.Ahead(RegexSpan(1, 4), LookaroundPolarity.POSITIVE))),
                ),
            )
        }

        @Test
        fun `declines extraction when the marker alphabet is exhausted`() {
            val occupied = ('\uE000'..'\uF8FF').joinToString("")
            val source = "$occupied(?=a)"
            assertThat(extract(source)).isEqualTo(RegexLookaroundExtraction.Unsupported("No assertion markers available"))
        }
    }

    private fun extract(source: String): RegexLookaroundExtraction = RegexLookaroundExtractor().extract(source, scanned(source).groups)

    private fun scanned(source: String): RegexScanner.Result.Scanned {
        val result = RegexScanner().scan(source)
        assertThat(result).isInstanceOf(RegexScanner.Result.Scanned::class.java)
        return result as RegexScanner.Result.Scanned
    }
}
