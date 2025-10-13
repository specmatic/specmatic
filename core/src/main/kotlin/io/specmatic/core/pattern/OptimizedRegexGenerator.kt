package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import kotlin.random.Random

internal const val WORD_BOUNDARY = "\\b"

class OptimizedRegexGenerator(val regex: String) {
    val isInfinite: Boolean get() { return Generex(regex).isInfinite }
    val isFinite: Boolean get() { return !Generex(regex).isInfinite }

    init {
        check(!regex.startsWith("/") && !regex.endsWith("/")) {
            "Invalid regex $regex. OpenAPI follows ECMA-262 regular expressions, which do not support / / delimiters like those used in many programming languages"
        }
    }

    fun random(minLength: Int? = 1, maxLength: Int? = REASONABLE_STRING_LENGTH): String {
        return generateOptimized(minLength ?: 1, maxLength ?: REASONABLE_STRING_LENGTH)
    }

    fun generateShortest(): String = RegExp(regex).toAutomaton().getShortestExample(true)

    /**
     * Recursively computes the longest accepted string (using at most [remaining] transitions)
     * from [state]. Returns null if no accepted string can be formed within the given limit.
     *
     * The tie-breaker when strings have the same length is the lexicographical order.
     */
    fun generateLongest(remaining: Int, state: State = RegExp(regex).toAutomaton().initialState, memo: MutableMap<Pair<State, Int>, String?> = mutableMapOf()): String? {
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

    private fun generateOptimized(minLength: Int, maxLength: Int): String {
        val automaton = RegExp(regex).toAutomaton()
        val builder = StringBuilder(maxLength.coerceAtMost(REASONABLE_STRING_LENGTH))
        return prepareRandomIterative(builder, automaton.initialState, minLength, maxLength)
    }

    private data class Frame(val text: StringBuilder, val state: State, val order: ArrayDeque<Int>, val builderLenAtStart: Int)

    private fun shuffledOrder(state: State, rng: Random): ArrayDeque<Int> {
        return ArrayDeque(state.getSortedTransitions(false).indices.shuffled(rng))
    }

    private fun shouldAccept(builderLength: Int, frame: Frame, min: Int, max: Int, rng: Random): Boolean {
        if (!frame.state.isAccept) return false
        if (builderLength == max) return true
        return builderLength >= min && rng.nextInt(100) >= 30
    }

    fun prepareRandomIterative(builder: StringBuilder, start: State, minLength: Int, maxLength: Int, rng: Random = Random): String {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(builder, start, shuffledOrder(start, rng), 0))

        while (stack.isNotEmpty()) {
            val frame = stack.last()
            val transitions = frame.state.getSortedTransitions(false)

            if (shouldAccept(frame.text.length, frame, minLength, maxLength, rng) || frame.order.isEmpty()) {
                return frame.text.toString()
            }

            if (frame.text.length > maxLength || frame.text.length < minLength && frame.order.isEmpty()) {
                stack.removeLast()
                frame.text.setLength(frame.builderLenAtStart)
                continue
            }

            val nextTransition = transitions[frame.order.removeFirst()]
            val diff = nextTransition.max.code - nextTransition.min.code + 1
            val offset = if (diff > 0) rng.nextInt(diff) else diff
            val randomChar = (nextTransition.min.code + offset).toChar()
            val builderLength = frame.text.length
            stack.addLast(Frame(
                frame.text.append(randomChar),
                nextTransition.dest,
                shuffledOrder(nextTransition.dest, rng),
                builderLength,
            ))
        }

        return builder.toString()
    }
}
