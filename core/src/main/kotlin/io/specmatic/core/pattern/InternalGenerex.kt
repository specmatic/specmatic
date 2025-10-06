package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import kotlin.random.Random

internal const val WORD_BOUNDARY = "\\b"

class InternalGenerex(val regex: String) {
    val regExp = RegExp(regex)
    val isInfinite: Boolean get() {
        return Generex(regex).isInfinite
    }

    init {
        check(!regex.startsWith("\\") && !regex.endsWith("\\")) {
            "Invalid regex $regex. OpenAPI follows ECMA-262 regular expressions, which do not support / / delimiters like those used in many programming languages"
        }
    }

    fun random(minLength: Int? = 1, maxLength: Int? = REASONABLE_STRING_LENGTH): String {
        return Generex(regex).generateOptimized(minLength ?: 1, maxLength ?: REASONABLE_STRING_LENGTH)
    }

    fun generateShortest(): String = regExp.toAutomaton().getShortestExample(true)

    /**
     * Recursively computes the longest accepted string (using at most [remaining] transitions)
     * from [state]. Returns null if no accepted string can be formed within the given limit.
     *
     * The tie-breaker when strings have the same length is the lexicographical order.
     */
    fun generateLongest(remaining: Int, state: State = regExp.toAutomaton().initialState, memo: MutableMap<Pair<State, Int>, String?> = mutableMapOf()): String? {
        val key = state to remaining
        memo[key]?.let { return it }

        var best: String? = if (state.isAccept) "" else null

        if (remaining > 0) {
            state.transitions.forEach { t ->
                val sub = generateLongest(remaining - 1, t.dest, memo)
                sub?.let {
                    val candidate = t.max.toString() + it
                    best = when {
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

    private fun Generex.generateOptimized(minLength: Int, maxLength: Int): String {
        val automaton = regExp.toAutomaton()
        val builder = StringBuilder(maxLength.coerceAtMost(REASONABLE_STRING_LENGTH))
        return prepareRandomTailRec(builder, automaton.initialState, minLength, maxLength)
    }

    private tailrec fun Generex.prepareRandomTailRec(builder: StringBuilder, state: State, minLength: Int, maxLength: Int, selectedTransitions: MutableSet<Int> = hashSetOf()): String {
        if (builder.length >= maxLength) return builder.toString()
        if (builder.length >= minLength && state.isAccept && Random.nextInt(100) < 30) return builder.toString()

        val transitions = state.getSortedTransitions(false)
        if (transitions.isEmpty() || selectedTransitions.size >= transitions.size) return builder.toString()

        val nextIdx = Random.nextInt(transitions.size)
        return if (selectedTransitions.add(nextIdx)) {
            val transition = transitions[nextIdx]
            val rangeWidth = transition.max - transition.min + 1
            val randomChar = (transition.min + Random.nextInt(rangeWidth)).toChar()
            prepareRandomTailRec(builder.append(randomChar), transition.dest, minLength, maxLength)
        } else {
            prepareRandomTailRec(builder, state, minLength, maxLength, selectedTransitions)
        }
    }
}
