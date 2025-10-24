package io.specmatic.core.pattern

import dk.brics.automaton.State
import dk.brics.automaton.Transition

data class Frame(
    var string: StringBuilder,
    var state: State,
) {
    private val transitionCount: Int
    private val availableTransitionIndices: MutableList<Int>

    init {
        transitionCount = getTransitions().size
        availableTransitionIndices = getTransitions().indices.shuffled().toMutableList()
    }

    fun getTransitions(): List<Transition> = state.getSortedTransitions(false)

    fun withdrawUnusedTransitionFromPool(): Transition {
        val transitions = getTransitions()

        val nextTransitionIndex = availableTransitionIndices.last()
        availableTransitionIndices.removeLast()
        return transitions[nextTransitionIndex]
    }

    fun hasNoTransitions(): Boolean = transitionCount == 0

    fun noTransitionsLeftToTry(): Boolean = availableTransitionIndices.isEmpty()

    fun deleteLastChar() {
        string.deleteCharAt(string.length - 1)
    }
}
