package io.specmatic.core.pattern

import dk.brics.automaton.State
import dk.brics.automaton.Transition
import kotlin.random.Random

class Frame(
    private var stringSoFar: StringBuilder = StringBuilder(),
    state: State,
) {
    private val isAccept = state.isAccept
    private val availableTransitions = state.getSortedTransitions(false).shuffled().toMutableList()
    private val originalTransitionCount = availableTransitions.size

    val stringLength: Int
        get() {
            return stringSoFar.length
        }

    fun buildString(): String {
        return stringSoFar.toString()
    }

    fun stringMatchesRegex(): Boolean = isAccept

    fun withdrawUnusedTransitionFromPool(): Transition {
        val transition = availableTransitions.last()
        availableTransitions.removeLast()
        return transition
    }

    fun hasNoTransitions(): Boolean = originalTransitionCount == 0

    fun allTransitionsHaveFailed(): Boolean = availableTransitions.isEmpty()

    fun dropLastChar() {
        stringSoFar.deleteCharAt(stringSoFar.length - 1)
    }

    private fun nextRandomChar(nextTransition: Transition): Char {
        val diff = nextTransition.getMax().code - nextTransition.getMin().code + 1
        val randomOffset =
            if (diff > 0) {
                Random.nextInt(diff)
            } else {
                diff
            }

        val randomChar = (randomOffset + nextTransition.getMin().code).toChar()
        return randomChar
    }

    fun apply(nextTransition: Transition): Frame {
        val randomChar = nextRandomChar(nextTransition)

        return Frame(
            stringSoFar.append(randomChar),
            nextTransition.dest,
        )
    }
}
