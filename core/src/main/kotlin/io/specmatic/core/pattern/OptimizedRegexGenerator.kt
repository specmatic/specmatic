package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import dk.brics.automaton.Transition
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import kotlin.random.Random
import kotlin.random.asJavaRandom

internal const val WORD_BOUNDARY = "\\b"

class OptimizedRegexGenerator(val regex: String) {
    val isInfinite: Boolean get() { return Generex(regex).isInfinite }
    val isFinite: Boolean get() { return !Generex(regex).isInfinite }
    val random = Random.asJavaRandom()

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
        return prepareRandomIterative2(regex, minLength, maxLength)

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
        if (builderLength >= max) return true
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

data class GeneratedSoFar(
    val stringSoFar: String,
    val exceededMaxLength: Boolean = false,
)

data class Frame(
    var strMatch: String,
    var state: State,
    val transitions: List<Transition>,
    var selectedTransitions: MutableSet<Int?> = mutableSetOf(),
)

class ExecutionStack {
    internal val executionStack = ArrayDeque<Frame>()
    private var _lastResult: GeneratedSoFar? = null

    fun returnedValue() = _lastResult

    fun returnValue(result: GeneratedSoFar) {
        executionStack.removeLast()
        _lastResult = result
    }

    fun dropLastResult() {
        _lastResult = null
    }

    fun currentFrame(): Frame? {
        return executionStack.lastOrNull()
    }

    fun addFrame(newFrame: Frame) {
        executionStack.addLast(newFrame)
        _lastResult = null
    }

}

private fun prepareRandomIterative2(
    regex: String,
    minLength: Int,
    maxLength: Int,
): String {
    val random = Random.asJavaRandom()
    val executionStack = ExecutionStack()

    val state = RegExp(regex).toAutomaton().initialState
    executionStack.addFrame(Frame("", state, state.getSortedTransitions(false).toList()))

    while (true) {
        val lastResult = executionStack.returnedValue()

        if (lastResult != null) {
            if (lastResult.exceededMaxLength) {
                executionStack.dropLastResult()
                continue
            }

            if (lastResult.stringSoFar.length in minLength..maxLength) {
                break
            }
        }

        val frame = executionStack.currentFrame() ?: break

        frame.strMatch = lastResult?.stringSoFar ?: frame.strMatch

        if (frame.transitions.size <= frame.selectedTransitions.size) {
            executionStack.returnValue(GeneratedSoFar(frame.strMatch))
            continue
        }

        if (frame.state.isAccept) {
            if (frame.strMatch.length == maxLength) {
                executionStack.returnValue(GeneratedSoFar(frame.strMatch))
                continue
            }

            if (frame.strMatch.length > maxLength) {
                executionStack.returnValue(GeneratedSoFar(frame.strMatch, true))
                continue
            }

            if (random.nextInt().toDouble() > 6.442450941E8 && frame.strMatch.length >= minLength) {
                executionStack.returnValue(GeneratedSoFar(frame.strMatch))
                continue
            }
        } else {
            if (frame.strMatch.length == maxLength) {
                executionStack.returnValue(GeneratedSoFar(frame.strMatch, true))
                continue
            }
        }

        if (frame.transitions.isEmpty()) {
            executionStack.returnValue(GeneratedSoFar(frame.strMatch, frame.state.isAccept))
            continue
        }

        val nextInt = random.nextInt(frame.transitions.size)
        if (!frame.selectedTransitions.contains(nextInt)) {
            frame.selectedTransitions.add(nextInt)
            val randomTransition = frame.transitions.get(nextInt)
            val diff = randomTransition.getMax().code - randomTransition.getMin().code + 1
            var randomOffset = diff
            if (diff > 0) {
                randomOffset = random.nextInt(diff)
            }

            val randomChar = (randomOffset + randomTransition.getMin().code).toChar()
            val newFrame =
                Frame(
                    frame.strMatch + randomChar,
                    randomTransition.dest,
                    randomTransition.dest.getSortedTransitions(false),
                )
            executionStack.addFrame(newFrame)
        }
    }

    return executionStack.returnedValue()?.stringSoFar ?: ""
}
