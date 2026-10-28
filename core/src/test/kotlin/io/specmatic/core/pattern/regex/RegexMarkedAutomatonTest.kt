package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton
import dk.brics.automaton.RegExp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class RegexMarkedAutomatonTest {
    @ParameterizedTest
    @ValueSource(strings = ["[ab]", "[^a]", ".", "[\u0000-\uFFFF]", "([ab]|<name>)+", "\\<class0\\>", "\"[^a]<class0>\"", "\"a\\\"[ab]"])
    fun `preserves native brics grammar while restricting consuming sets to candidates`(expression: String) {
        val ascii = Automaton.makeCharRange('\u0000', '\u007F')
        val expected = RegExp(expression, 0).toAutomaton().intersection(ascii.repeat())
        assertThat(RegexMarkedAutomaton(ascii).compile(expression)).isEqualTo(expected)
    }
}
