package io.specmatic.core.pattern.regex

import dk.brics.automaton.State
import dk.brics.automaton.Transition
import kotlin.random.Random

class Stage(
    private var stringSoFar: StringBuilder = StringBuilder(),
    state: State,
) {
    private val stringMatchesRegex = state.isAccept
    private val availableTransitions = state.getSortedTransitions(false).shuffled().toMutableList()
    private val originalTransitionCount = availableTransitions.size

    private fun removeUnusedTransitionFromPool(): Transition {
        val transition = availableTransitions.last()
        availableTransitions.removeLast()
        return transition
    }

    private fun hasNoTransitions(): Boolean = originalTransitionCount == 0

    private fun allTransitionsHaveFailed(): Boolean = availableTransitions.isEmpty()

    fun dropLastChar() {
        stringSoFar.deleteCharAt(stringSoFar.length - 1)
    }

    private fun nextRandomChar(nextTransition: Transition): Char {
        val diff = nextTransition.getMax().code - nextTransition.getMin().code + 1
        val randomOffset =
            if (diff > 0) {
                Random.Default.nextInt(diff)
            } else {
                diff
            }

        val randomChar = (randomOffset + nextTransition.getMin().code).toChar()
        return randomChar
    }

    fun computeNext(
        minLength: Int,
        maxLength: Int,
    ): ComputationResult {
        if (stringSoFar.length > maxLength) {
            return ComputationPathIsLostCause
        }

        val cannotGenerateAnotherCharacter = hasNoTransitions() || allTransitionsHaveFailed()

        if (stringMatchesRegex) {
            when (stringSoFar.length) {
                maxLength -> {
                    return Answer(stringSoFar.toString())
                }

                in minLength..maxLength -> {
                    if (cannotGenerateAnotherCharacter || coinTossSaysToTerminate()) {
                        return Answer(stringSoFar.toString())
                    }
                }

                else -> {
                    if (cannotGenerateAnotherCharacter) {
                        return ComputationPathIsLostCause
                    }
                }
            }
        } else {
            if (stringSoFar.length == maxLength || this.allTransitionsHaveFailed()) {
                return ComputationPathIsLostCause
            }
        }

        val nextTransition = this.removeUnusedTransitionFromPool()

        val randomChar = nextRandomChar(nextTransition)

        return NextStage(
            Stage(
                stringSoFar.append(randomChar),
                nextTransition.dest,
            ),
        )
    }

    private fun coinTossSaysToTerminate(): Boolean = Random.nextInt().toDouble() > 6.442450941E8
}
