package io.specmatic.core.pattern.regex

import dk.brics.automaton.State
import dk.brics.automaton.Transition
import kotlin.random.Random

class Stage(
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

    fun buildString(): String = stringSoFar.toString()

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
                Random.Default.nextInt(diff)
            } else {
                diff
            }

        val randomChar = (randomOffset + nextTransition.getMin().code).toChar()
        return randomChar
    }

    fun apply(nextTransition: Transition): Stage {
        val randomChar = nextRandomChar(nextTransition)

        return Stage(
            stringSoFar.append(randomChar),
            nextTransition.dest,
        )
    }

    fun computeNext(
        minLength: Int,
        maxLength: Int,
    ): ComputationResult {
        if (this.stringLength > maxLength) {
            return ComputationPathIsLostCause
        }

        val cannotGenerateAnotherCharacter = this.hasNoTransitions() || this.allTransitionsHaveFailed()

        if (this.stringMatchesRegex()) {
            when (this.stringLength) {
                maxLength -> {
                    return FoundAnswer(this.buildString())
                }

                in minLength..maxLength -> {
                    if (cannotGenerateAnotherCharacter || coinTossSaysToTerminate()) {
                        return FoundAnswer(this.buildString())
                    }
                }

                else -> {
                    if (cannotGenerateAnotherCharacter) {
                        return ComputationPathIsLostCause
                    }
                }
            }
        } else {
            if (this.stringLength == maxLength || this.allTransitionsHaveFailed()) {
                return ComputationPathIsLostCause
            }
        }

        val nextTransition = this.withdrawUnusedTransitionFromPool()
        return NextStage(this.apply(nextTransition))
    }

    private fun coinTossSaysToTerminate(): Boolean =
        Random
            .nextInt()
            .toDouble() > 6.442450941E8
}
