package io.specmatic.core.pattern.regex

import dk.brics.automaton.Automaton

internal class RegexCandidateAlphabet(assertions: List<MarkedAssertion>) {
    private val markers = assertions.map { it.marker }.joinToString("")
    val character: Automaton = Automaton.makeAnyChar().minus(Automaton.makeCharSet(markers))
    val strings: Automaton = character.repeat()
    val contextRegex: String = "[^$markers]*"

    fun otherMarkerStrings(marker: Char): Automaton {
        return Automaton.makeCharSet(markers.filter { it != marker }).repeat()
    }
}
