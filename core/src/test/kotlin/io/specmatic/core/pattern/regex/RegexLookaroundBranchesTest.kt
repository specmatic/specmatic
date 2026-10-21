package io.specmatic.core.pattern.regex

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class RegexLookaroundBranchesTest {
    @Nested
    inner class TopLevelAlternatives {
        @Test
        fun `separates complete branches with independent anchors`() {
            assertThat(split("^a(?!b)b$|^ab$")).containsExactly("^a(?!b)b$", "^ab$")
        }

        @Test
        fun `retains empty branches and ignores escaped class and body alternatives`() {
            assertThat(split("|(?=a|b)[a|b]\\||")).containsExactly("", "(?=a|b)[a|b]\\|", "")
        }

        @Test
        fun `splits the root before alternatives inside groups`() {
            assertThat(split("^((?=a)a|b)$|^c$")).containsExactly("^((?=a)a|b)$", "^c$")
        }
    }

    @Nested
    inner class OrdinaryGroupAlternatives {
        @Test
        fun `preserves surrounding consuming context and anchors`() {
            assertThat(split("^x(a(?=b)b|c)y$")).containsExactly("^x(a(?=b)b)y$", "^x(c)y$")
        }

        @Test
        fun `retains named group wrappers during branch expansion`() {
            assertThat(split("^(?<value>a(?=b)b|c)$")).containsExactly("^(?<value>a(?=b)b)$", "^(?<value>c)$")
        }

        @Test
        fun `expands one assertion dependent group at a time`() {
            assertThat(split("^((?=a)a|b)((?=c)c|d)$")).containsExactly(
                "^((?=a)a)((?=c)c|d)$", "^(b)((?=c)c|d)$",
            )
        }

        @ParameterizedTest
        @ValueSource(strings = ["^(?=(a|b)+)[ab]+$", "^(a|b)(?=c)c$", "(?<=(ab|ac))d"])
        fun `leaves alternatives unrelated to assertion occurrence unchanged`(source: String) {
            assertThat(split(source)).containsExactly(source)
        }
    }

    private fun split(source: String): List<String> {
        val scanned = RegexScanner().scan(source)
        assertThat(scanned).isInstanceOf(RegexScanner.Result.Scanned::class.java)
        return RegexLookaroundBranches().split(source, scanned as RegexScanner.Result.Scanned)
    }
}
