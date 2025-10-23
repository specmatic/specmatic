package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import dk.brics.automaton.Transition
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
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
        val executionStack = ArrayDeque<Frame>()

        val state = RegExp(regex).toAutomaton().initialState
        executionStack.addLast(Frame("", state, state.getSortedTransitions(false).toList(), random = random))

        return generate(executionStack, minLength, maxLength)
    }

    val random = Random.asJavaRandom()

    private fun generate(
        executionStack: ArrayDeque<Frame>,
        minLength: Int,
        maxLength: Int,
    ): String {
        var result: String? = null

        while (true) {
            val frame = executionStack.lastOrNull() ?: break

            if (frame.strMatch.length > maxLength) {
                executionStack.removeLast()
                continue
            }

            val noTransitionsLeft = frame.hasNoTransitions() || frame.noTransitionsLeftInPool()

            if (frame.state.isAccept) {
                if (frame.strMatch.length == maxLength ||
                    (frame.strMatch.length >= minLength && (noTransitionsLeft || randomBooleanIsTrue()))
                ) {
                    result = frame.strMatch
                    break
                }
            } else {
                if (frame.strMatch.length == maxLength || frame.noTransitionsLeftInPool()) {
                    executionStack.removeLast()
                    continue
                }
            }

            val nextTransition = frame.withdrawUnusedTransitionFromPool()

            val randomChar = nextRandomChar(nextTransition)

            val newFrame =
                Frame(
                    frame.strMatch + randomChar,
                    nextTransition.dest,
                    nextTransition.dest.getSortedTransitions(false),
                    random = random,
                )
            executionStack.addLast(newFrame)
        }

        return result.orEmpty()
    }

    private fun randomBooleanIsTrue(): Boolean =
        random
            .nextInt()
            .toDouble() > 6.442450941E8

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
