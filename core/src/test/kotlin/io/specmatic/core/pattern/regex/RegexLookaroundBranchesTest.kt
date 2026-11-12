package io.specmatic.core.pattern.regex

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class RegexLookaroundBranchesTest {
    @Nested
    inner class MutuallyExclusiveScopes {
        @Test
        fun `partitions complete branches with different anchors`() {
            assertThat(split("^a(?!b)b$|ab")).containsExactly("^a(?!b)b$", "ab")
        }

        @Test
        fun `retains empty branches and ignores class escapes and body alternatives`() {
            assertThat(split("|(?=a|b)[a|b]\\||")).containsExactly("", "(?=a|b)[a|b]\\|", "")
        }

        @Test
        fun `partitions the outermost exclusive assertion scope first`() {
            assertThat(split("^(((?=a)a|b)|(?=c)c)$")).containsExactly("^(((?=a)a|b))$", "^((?=c)c)$")
        }

        @Test
        fun `preserves surrounding context when all assertions belong to one disjunction`() {
            assertThat(split("^x(a(?=b|c)[bc]|d|e)y$")).containsExactly(
                "^x(a(?=b|c)[bc])y$", "^x(d)y$", "^x(e)y$",
            )
        }

        @Test
        fun `preserves named group wrappers`() {
            assertThat(split("^(?<value>a(?=b)b|c)$")).containsExactly("^(?<value>a(?=b)b)$", "^(?<value>c)$")
        }

        @ParameterizedTest
        @ValueSource(strings = ["(?=[ac])", "(?=z)?"])
        fun `retains shared unconditional or omitted checks without distributing independent choices`(prefix: String) {
            assertThat(split("$prefix(a(?=b)b|c)")).containsExactly("$prefix(a(?=b)b)", "$prefix(c)")
        }

        @Test
        fun `preserves an unconditional lookbehind across the selected alternatives`() {
            assertThat(split("^a((?=b)b|c)(?<=ab)d$")).containsExactly("^a((?=b)b)(?<=ab)d$", "^a(c)(?<=ab)d$")
        }

        @Test
        fun `ignores directly skippable checks when locating conditional scopes`() {
            assertThat(split("((?=z)?a|b)((?=c)c|d)")).containsExactly("((?=z)?a|b)((?=c)c)", "((?=z)?a|b)(d)")
        }
    }

    @Nested
    inner class IndependentScopes {
        @ParameterizedTest
        @ValueSource(strings = [
            "^((?=a)a|b)((?=c)c|d)$",
            "^(?=(a|b)+)[ab]+$", "^(a|b)(?=c)c$", "(?<=(ab|ac))d", "^(a|b)(c|d)$",
        ])
        fun `retains independent choices and assertion body alternatives inside dk brics`(source: String) {
            assertThat(split(source)).containsExactly(source)
        }
    }

    private fun split(source: String): List<String> {
        val scanned = RegexScanner().scan(source) as RegexScanner.Result.Scanned
        return RegexLookaroundBranches().split(source, scanned)
    }
}
