package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import dk.brics.automaton.Transition
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
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
    ): String {
        val state = RegExp(regex).toAutomaton().initialState
        val executionStack =
            ExecutionStack(Frame(StringBuilder(), state))
        return generate(executionStack, minLength ?: 1, maxLength ?: REASONABLE_STRING_LENGTH)
    }

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

    val random = Random.asJavaRandom()

    private fun generate(
        executionStack: ExecutionStack,
        minLength: Int,
        maxLength: Int,
    ): String {
        var result: String? = null

        while (true) {
            val frame = executionStack.lastFrame() ?: break

            if (frame.string.length > maxLength) {
                executionStack.backtrack()
                continue
            }

            val cannotGenerateMoreCharacters = frame.hasNoTransitions() || frame.noTransitionsLeftInPool()

            if (frame.state.isAccept) {
                when (frame.string.length) {
                    maxLength -> {
                        result = frame.string.toString()
                        break
                    }

                    in minLength..maxLength -> {
                        if (cannotGenerateMoreCharacters || randomBooleanIsTrue()) {
                            result = frame.string.toString()
                            break
                        }
                    }

                    else -> {
                        if (cannotGenerateMoreCharacters) {
                            executionStack.backtrack()
                            continue
                        }
                    }
                }
            } else {
                if (frame.string.length == maxLength || frame.noTransitionsLeftInPool()) {
                    executionStack.backtrack()
                    continue
                }
            }

            val nextTransition = frame.withdrawUnusedTransitionFromPool()

            val randomChar = nextRandomChar(nextTransition)

            val newFrame =
                Frame(
                    frame.string.append(randomChar),
                    nextTransition.dest,
                )
            executionStack.addFrame(newFrame)
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

