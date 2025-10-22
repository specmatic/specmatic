package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import dk.brics.automaton.Transition
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import io.specmatic.core.pattern.regex.ComputationResult
import io.specmatic.core.pattern.regex.ExecutionStack
import kotlin.random.Random
import kotlin.random.asJavaRandom

internal const val WORD_BOUNDARY = "\\b"

class OptimizedRegexGenerator(
    val regex: String,
) {
    val isInfinite: Boolean
        get() {
            return Generex(regex).isInfinite
        }
    val isFinite: Boolean
        get() {
            return !Generex(regex).isInfinite
        }

    init {
        check(!regex.startsWith("/") && !regex.endsWith("/")) {
            "Invalid regex $regex. OpenAPI follows ECMA-262 regular expressions, which do not support / / delimiters like those used in many programming languages"
        }
    }

    fun random(
        minLength: Int? = 1,
        maxLength: Int? = REASONABLE_STRING_LENGTH,
    ): String = generate(minLength ?: 1, maxLength ?: REASONABLE_STRING_LENGTH)

    fun generateShortest(): String = RegExp(regex).toAutomaton().getShortestExample(true)

    /**
     * Recursively computes the longest accepted string (using at most [remaining] transitions)
     * from [state]. Returns null if no accepted string can be formed within the given limit.
     *
     * The tie-breaker when strings have the same length is the lexicographical order.
     */
    fun generateLongest(
        remaining: Int,
        state: State = RegExp(regex).toAutomaton().initialState,
        memo: MutableMap<Pair<State, Int>, String?> = mutableMapOf(),
    ): String? {
        val key = state to remaining
        memo[key]?.let { return it }

        var best: String? = if (state.isAccept) "" else null

        if (remaining > 0) {
            state.transitions.forEach { t ->
                val sub = generateLongest(remaining - 1, t.dest, memo)
                sub?.let {
                    val candidate = t.max.toString() + it
                    best =
                        when {
                            best == null -> candidate
                            candidate.length > best!!.length -> candidate
                            candidate.length == best!!.length && candidate > best!! -> candidate
                            else -> best
                        }
                }
            }
        }

        memo[key] = best
        return best
    }

    private fun generate(
        minLength: Int,
        maxLength: Int,
    ): String {
        val executionStack = ExecutionStack()

        val state = RegExp(regex).toAutomaton().initialState
        executionStack.addFrame(Frame("", state, state.getSortedTransitions(false).toList(), random = random))

        return generate(executionStack, minLength, maxLength)
    }

    val random = Random.asJavaRandom()

    private fun generate(
        executionStack: ExecutionStack,
        minLength: Int,
        maxLength: Int,
    ): String {
        while (true) {
            val lastResult = executionStack.returnedValue()

            if (lastResult != null) {
                if (lastResult.dropResult) {
                    executionStack.dropLastResult()
                    continue
                }

                if (lastResult.string.length in minLength..maxLength && lastResult.acceptableState) {
                    break
                }
            }

            val frame = executionStack.currentFrame() ?: break

            if (frame.allTransitionsAreWithdrawn()) {
                val result =
                    ComputationResult(
                        frame.strMatch,
                        dropResult = !frame.state.isAccept,
                        acceptableState = frame.state.isAccept,
                    )

                executionStack.returnValue(
                    result,
                )
                continue
            }

            frame.strMatch = lastResult?.string ?: frame.strMatch

            if (frame.strMatch.length > maxLength) {
                executionStack.returnValue(
                    ComputationResult(
                        frame.strMatch,
                        dropResult = true,
                        acceptableState = frame.state.isAccept,
                    ),
                )
                continue
            }

            if (frame.state.isAccept) {
                if (frame.strMatch.length == maxLength || (
                        random
                            .nextInt()
                            .toDouble() > 6.442450941E8 && frame.strMatch.length >= minLength
                    )
                ) {
                    executionStack.returnValue(
                        ComputationResult(
                            frame.strMatch,
                            acceptableState = frame.state.isAccept,
                        ),
                    )
                    continue
                }
            } else if (frame.strMatch.length == maxLength) {
                executionStack.returnValue(
                    ComputationResult(
                        frame.strMatch,
                        dropResult = true,
                        acceptableState = frame.state.isAccept,
                    ),
                )
                continue
            }

            if (frame.hasNoTransitions()) {
                executionStack.returnValue(ComputationResult(frame.strMatch, acceptableState = frame.state.isAccept))
                continue
            }

            val nextTransition = frame.withdrawUnusedTransitionIndex()

            val randomChar = nextRandomChar(nextTransition)

            val newFrame =
                Frame(
                    frame.strMatch + randomChar,
                    nextTransition.dest,
                    nextTransition.dest.getSortedTransitions(false),
                    random = random,
                )
            executionStack.addFrame(newFrame)
        }

        return executionStack.returnedValue()?.string ?: ""
    }

    private fun nextRandomChar(nextTransition: Transition): Char {
        val diff = nextTransition.getMax().code - nextTransition.getMin().code + 1
        val randomOffset =
            if (diff > 0) {
                random.nextInt(diff)
            } else {
                diff
            }

        val randomChar = (randomOffset + nextTransition.getMin().code).toChar()
        return randomChar
    }
}
