package io.specmatic.core.pattern.regex

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class RegexLookaroundSupportTest {
    @Nested
    inner class UnsupportedScopeAndPlacement {
        @ParameterizedTest(name = "{0}")
        @CsvSource(
            delimiter = '→',
            value = [
                "(?i)(?=a)a→Flag groups are outside flagless lookaround support",
                "(?i:a)(?=a)a→Flag groups are outside flagless lookaround support",
                "^(?=(a))\\1$→Backreferences are outside lookaround generation support",
                "^(?=(?<name>a))\\k<name>$→Backreferences are outside lookaround generation support",
                "^(?=a)(a)()()()()()()()\\8$→Backreferences are outside lookaround generation support",
                "(?=a)a|b→Top-level alternatives containing assertions are unsupported",
                "(?=a)?a→Only exact-once lookahead quantifiers are supported",
                "(?=a)+a→Only exact-once lookahead quantifiers are supported",
                "(?=a){2}a→Only exact-once lookahead quantifiers are supported",
                "(?!a)*b→Only exact-once lookahead quantifiers are supported",
                "a(?<=a){1}→ECMAScript lookbehind cannot be quantified",
                "a(?<!b){1,1}→ECMAScript lookbehind cannot be quantified",
                "(?=a$)a→Anchors inside assertion bodies are unsupported",
                "(?<=^a)b→Anchors inside assertion bodies are unsupported",
                "(?=(?!a)b)b→Nested lookarounds are unsupported",
                "(?<=(?=a)a)b→Nested lookarounds are unsupported",
                "((?=a)a)?→Assertions inside optional or repeated groups are unsupported",
                "((?=a)a)+→Assertions inside optional or repeated groups are unsupported",
                "((?=a)a){0}→Assertions inside optional or repeated groups are unsupported",
                "((?=a)a|b)→Assertions inside alternative branches are unsupported",
                "(?:a|(?=b)b)→Assertions inside alternative branches are unsupported",
            ],
        )
        fun `returns the complete reason for each unsupported construct`(source: String, expectedReason: String) {
            assertThat(unsupportedReason(source)).isEqualTo(expectedReason)
        }
    }

    @Nested
    inner class IndependentAssertions {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "^(?=(?:ab|ac)+)(ab|ac){1,2}$",
                "^((a|b)(?=c)c)$",
                "^((?=a)a)((?=b)b)$",
                "^a(?=b)b(?<=ab)(?!d)c$",
                "(?=a){1}a",
                "(?=a){1,1}a",
                "(?!b){1}?a",
                "((?=a)a){1}",
                "((?=a)a){1,1}?",
                "(?=[^$])[\\^$]",
            ],
        )
        fun `accepts ordinary grouping alternatives and exact once assertions`(source: String) {
            assertThat(unsupportedReason(source)).isNull()
        }
    }

    @Nested
    inner class InheritedCleanupBehavior {
        @ParameterizedTest(name = "{0}")
        @ValueSource(
            strings = [
                "^(?![\\D_])_$",
                "^(?![a-\\d]).$",
                "^(?=\\t)\\t$",
                "^(?=\\x61)a$",
                "^(?=\\u0061)a$",
                "^(?=\\cA).$",
                "^(?=a)\\ba$",
                "^(?=[\\b])[\\b]$",
                "^(?=a)\\0$",
                "^(?=a)\\01$",
                "^(?=a)\\k<name>$",
                "^(?=a)[\\1]$",
                "^(?=a)(?<name>a)$",
                "^(?=a)\\Qa\\E$",
                "^(?=a)\"a\"$",
                "^(?=a+?)a+?$",
                "^(?=a{,2})a{,2}$",
                "(?=a)[\\",
            ],
        )
        fun `does not introduce additional cleanup compatibility restrictions`(source: String) {
            assertThat(unsupportedReason(source)).isNull()
        }
    }

    private fun unsupportedReason(source: String): String? {
        val scanned = RegexScanner().scan(source)
        assertThat(scanned).isInstanceOf(RegexScanner.Result.Scanned::class.java)
        return RegexLookaroundSupport(source, scanned as RegexScanner.Result.Scanned).unsupportedReason()
    }
}
