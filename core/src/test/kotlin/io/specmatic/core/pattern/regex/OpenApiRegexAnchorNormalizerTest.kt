package io.specmatic.core.pattern.regex

import dk.brics.automaton.RegExp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

internal class OpenApiRegexAnchorNormalizerTest {
    private val normalizer = OpenApiRegexAnchorNormalizer()

    @Nested
    inner class FullyAnchoredPatterns {
        @ParameterizedTest
        @CsvSource(
            delimiter = '→',
            value = [
                "^foo$→foo",
                "^$→()",
                "^^foo$$→foo",
                "^^$$→()",
                "^[A-Za-z0-9._\\-]{0,64}$→[A-Za-z0-9._\\-]{0,64}",
                "^foo\\$$→foo\\$",
                "^foo\\^bar$→foo\\^bar",
                "^[a^$]+$→[a^$]+",
                "^[^$]+$→[^$]+",
                "^foo\\|bar$→foo\\|bar",
            ],
        )
        fun `normalizes fully anchored patterns`(regex: String, expected: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(OpenApiRegexAnchorNormalizer.Result.Normalized(expected))
        }
    }

    @Nested
    inner class PartiallyAnchoredPatterns {
        @ParameterizedTest
        @CsvSource(
            delimiter = '→',
            value = [
                "^foo→foo.*",
                "foo$→.*foo",
                "^[a-z0-9]{6,10}→[a-z0-9]{6,10}.*",
                "[a-z0-9]{6,10}$→.*[a-z0-9]{6,10}",
                "^foo\\$→foo\\$.*",
                "\\^foo$→.*\\^foo",
                "^→.*",
                "$→.*",
                "^^→.*",
                "$$→.*",
            ],
        )
        fun `adds an arbitrary suffix or prefix for a missing boundary`(regex: String, expected: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(OpenApiRegexAnchorNormalizer.Result.Normalized(expected))
        }
    }

    @Nested
    inner class Alternatives {
        @ParameterizedTest
        @CsvSource(
            delimiter = '→',
            value = [
                "^$|^[A-Za-z0-9._\\-]{1,64}$→()|[A-Za-z0-9._\\-]{1,64}",
                "^foo$|^bar$→foo|bar",
                "^foo|bar$→foo.*|.*bar",
                "^foo$|bar→foo|.*bar.*",
                "foo|^bar$→.*foo.*|bar",
                "^foo|bar→foo.*|.*bar.*",
                "foo|bar$→.*foo.*|.*bar",
                "|^foo$→.*|foo",
                "^foo$|→foo|.*",
                "^$|$→()|.*",
                "^|foo$→.*|.*foo",
            ],
        )
        fun `normalizes each top level alternative independently`(regex: String, expected: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(OpenApiRegexAnchorNormalizer.Result.Normalized(expected))
        }
    }

    @Nested
    inner class WholeExpressionGroups {
        @ParameterizedTest
        @CsvSource(
            delimiter = '→',
            value = [
                "(^foo$|^bar$)→(foo|bar)",
                "(?:^foo$|^bar$)→(?:foo|bar)",
                "^(foo|bar)$→(foo|bar)",
                "^(?:foo|bar)$→(?:foo|bar)",
                "^(^foo$|bar)$→(foo|bar)",
                "^(foo$|bar$)→(foo|bar)",
                "(^foo|^bar)$→(foo|bar)",
                "^(foo|bar)→(foo.*|bar.*)",
                "(foo|bar)$→(.*foo|.*bar)",
                "^(foo|bar$)→(foo.*|bar)",
                "(^foo|bar)$→(foo|.*bar)",
                "(foo$|bar$)→(.*foo|.*bar)",
                "(^foo|^bar)→(foo.*|bar.*)",
                "(foo|^bar$)→(.*foo.*|bar)",
                "^((^foo$|bar))$→((foo|bar))",
                "^(|foo)$→(()|foo)",
                "^(foo|)$→(foo|())",
                "^(foo||bar)$→(foo|()|bar)",
                "^(|foo)→(.*|foo.*)",
                "(|foo)$→(.*|.*foo)",
            ],
        )
        fun `propagates inherited boundaries through whole expression groups`(regex: String, expected: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(OpenApiRegexAnchorNormalizer.Result.Normalized(expected))
        }
    }

    @Nested
    inner class LiteralAnchorCharacters {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "",
                "foo",
                "foo|bar",
                "\\^foo\\$",
                "[a^$]",
                "[^$]",
                "[\\^$]",
                "foo\\|bar",
                "(foo|bar)",
            ],
        )
        fun `leaves patterns without anchor assertions unchanged`(regex: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(OpenApiRegexAnchorNormalizer.Result.Unchanged(regex))
        }
    }

    @Nested
    inner class UnsupportedAnchorPositions {
        @ParameterizedTest
        @ValueSource(
            strings = [
                "foo^bar",
                $$"foo$bar",
                "^foo^bar$",
                "x(^foo$|bar)y",
                "(^foo$|bar)+",
                "^x(^foo$|bar)$",
                "foo(?=^bar$)",
            ],
        )
        fun `falls back when an anchor occurs inside a non whole expression position`(regex: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(
                    OpenApiRegexAnchorNormalizer.Result.Unsupported(
                        regex = regex,
                        reason = "Anchor assertion occurs in a position that cannot be normalized safely: $regex",
                    ),
                )
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "(?=^foo$)",
                "(?!^foo$)",
                "^(?=^foo$)$",
            ],
        )
        fun `falls back for unsupported whole group constructs containing anchors`(regex: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(
                    OpenApiRegexAnchorNormalizer.Result.Unsupported(
                        regex = regex,
                        reason = "Anchor normalization does not support this (?...) group construct",
                    ),
                )
        }
    }

    @Nested
    inner class MalformedPatterns {
        @ParameterizedTest
        @CsvSource(
            delimiter = '→',
            value = [
                "^(foo$→Regex contains an unclosed group",
                "^foo[bar$→Regex contains an unclosed character class",
                "^foo$\\→Regex ends with an incomplete escape sequence",
                "^foo$)→Unexpected ')' at index 5",
            ],
        )
        fun `falls back with the structural parsing reason`(regex: String, reason: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(
                    OpenApiRegexAnchorNormalizer.Result.Unsupported(
                        regex = regex,
                        reason = reason,
                    ),
                )
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "(foo",
                "foo[bar",
                "foo\\",
            ],
        )
        fun `does not validate malformed patterns that contain no anchor assertions`(regex: String) {
            assertThat(normalizer.normalize(regex)).isEqualTo(OpenApiRegexAnchorNormalizer.Result.Unchanged(regex))
        }
    }

    @Nested
    inner class AutomatonSemantics {
        @Test
        fun `reported old and new patterns produce equivalent automata`() {
            val oldPattern = "^$|^[A-Za-z0-9._\\-]{1,64}$"
            val newPattern = "^[A-Za-z0-9._\\-]{0,64}$"

            val oldAutomaton = RegExp(normalizer.normalize(oldPattern).regex, 0).toAutomaton()
            val newAutomaton = RegExp(normalizer.normalize(newPattern).regex, 0).toAutomaton()

            assertThat(oldAutomaton).isEqualTo(newAutomaton)
        }

        @ParameterizedTest(name = "{0}: /{1}/ against \"{2}\" => {3}")
        @MethodSource("io.specmatic.core.pattern.regex.OpenApiRegexAnchorNormalizerTest#boundarySemanticsCases")
        fun `regex boundary semantics`(description: String, pattern: String, input: String, expected: Boolean) {
            val regex = normalizer.normalize(pattern).regex
            val automaton = RegExp(regex, 0).toAutomaton()
            assertThat(automaton.run(input)).isEqualTo(expected)
        }
    }

    companion object {
        @JvmStatic
        fun boundarySemanticsCases() = listOf(
            // Start anchored pattern
            Arguments.of("start anchored", "^[a-z0-9]{6,10}", "abcdef", true),
            Arguments.of("start anchored", "^[a-z0-9]{6,10}", "abcdefXYZ", true),
            Arguments.of("start anchored", "^[a-z0-9]{6,10}", "abcdef-whatever", true),
            Arguments.of("start anchored", "^[a-z0-9]{6,10}", "XYZabcdef", false),
            Arguments.of("start anchored", "^[a-z0-9]{6,10}", "abcde", false),

            // End anchored pattern
            Arguments.of("end anchored", "[a-z0-9]{6,10}$", "abcdef", true),
            Arguments.of("end anchored", "[a-z0-9]{6,10}$", "XYZabcdef", true),
            Arguments.of("end anchored", "[a-z0-9]{6,10}$", "--abcdef", true),
            Arguments.of("end anchored", "[a-z0-9]{6,10}$", "abcdefXYZ", false),
            Arguments.of("end anchored", "[a-z0-9]{6,10}$", "abcde", false),

            // Mixed alternatives
            Arguments.of("mixed alternatives", "^foo|bar$", "foo", true),
            Arguments.of("mixed alternatives", "^foo|bar$", "foobar", true),
            Arguments.of("mixed alternatives", "^foo|bar$", "xxfoo", false),
            Arguments.of("mixed alternatives", "^foo|bar$", "bar", true),
            Arguments.of("mixed alternatives", "^foo|bar$", "xxbar", true),
            Arguments.of("mixed alternatives", "^foo|bar$", "barxx", false),
        )
    }
}
