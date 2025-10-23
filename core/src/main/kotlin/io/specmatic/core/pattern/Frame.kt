package io.specmatic.core.pattern

import dk.brics.automaton.State
import dk.brics.automaton.Transition
import java.util.Random

data class Frame(
    var strMatch: StringBuilder,
    var state: State,
    var usedTransitionIndices: MutableSet<Int?> = mutableSetOf(),
    val random: Random
) {
    val transitionCount = getTransitions().size

    fun getTransitions(): List<Transition> {
        return state.getSortedTransitions(false)
    }

    fun withdrawUnusedTransitionFromPool(): Transition {
        val transitions = getTransitions()

        val remainingTransitionsWithIndex =
            transitions.mapIndexed { index, item -> index to item }.filter { (index, _) ->
                index !in usedTransitionIndices
            }

        val nextRemainingTransition = random.nextInt(remainingTransitionsWithIndex.size)
        val nextTransitionIndex = remainingTransitionsWithIndex[nextRemainingTransition].first

        usedTransitionIndices.add(nextTransitionIndex)

        return transitions[nextTransitionIndex]
    }

    fun hasNoTransitions(): Boolean {
        return transitionCount == 0
    }

    fun noTransitionsLeftInPool(): Boolean {
        return transitionCount <= usedTransitionIndices.size
    }

    fun deleteLastChar() {
        strMatch.deleteCharAt(strMatch.length - 1)
    }
}
