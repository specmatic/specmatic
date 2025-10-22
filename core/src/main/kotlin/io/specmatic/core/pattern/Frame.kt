package io.specmatic.core.pattern

import dk.brics.automaton.State
import dk.brics.automaton.Transition
import java.util.Random

data class Frame(
    var strMatch: String,
    var state: State,
    val transitions: List<Transition>,
    var usedTransitionIndices: MutableSet<Int?> = mutableSetOf(),
    val random: Random
) {
    fun withdrawUnusedTransitionIndex(): Transition {
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
        return transitions.isEmpty()
    }

    fun allTransitionsAreWithdrawn(): Boolean {
        return transitions.size <= usedTransitionIndices.size
    }
}
