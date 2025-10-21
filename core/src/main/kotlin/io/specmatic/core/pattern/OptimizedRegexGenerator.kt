package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import dk.brics.automaton.Transition
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import io.specmatic.core.log.logger
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
    }
}

data class ComputedSoFar(
    val stringSoFar: String,
    val dropResult: Boolean = false,
    val acceptableState: Boolean
)

data class Frame(
    var strMatch: String,
    var state: State,
    val transitions: List<Transition>,
    var selectedTransitions: MutableSet<Int?> = mutableSetOf(),
    val id: Int = Companion.id++
) {
    companion object {
        private var id = 0
    }
}

class ExecutionStack {
    internal val executionStack = ArrayDeque<Frame>()
    private var _lastResult: ComputedSoFar? = null

    fun returnedValue() = _lastResult

    fun returnValue(result: ComputedSoFar) {
        val lastFrame = executionStack.last()
        logger.debug("Removing ${lastFrame.hashCode()} ${executionStack.last()}")
        executionStack.removeLast()
        logger.debug("Storing result $result")
        logger.debug("${executionStack.size} frames left")
        logger.boundary()
        _lastResult = result
    }

    fun dropLastResult() {
        logger.debug("Dropping last result ${_lastResult ?: "null result"}")
        logger.boundary()
        _lastResult = null
    }

    fun currentFrame(): Frame? {
        return executionStack.lastOrNull()
    }

    fun addFrame(newFrame: Frame) {
        logger.debug("Adding ${newFrame.hashCode()} $newFrame")
        executionStack.addLast(newFrame)
        logger.debug("${executionStack.size} frames on stack")
        logger.boundary()
        _lastResult = null
    }

}

private fun prepareRandomIterative2(
    regex: String,
    minLength: Int,
    maxLength: Int,
): String {
    logger.debug("=== Starting computation for regex: $regex -> minLength $minLength, maxLength $maxLength")
    val random = Random.asJavaRandom()
    val executionStack = ExecutionStack()

    val state = RegExp(regex).toAutomaton().initialState
    executionStack.addFrame(Frame("", state, state.getSortedTransitions(false).toList()))

    while (true) {
        logger.debug("Current frame: ${executionStack.currentFrame()?.hashCode()} ${executionStack.currentFrame() ?: "no frames left"}")
        logger.boundary()

        val lastResult = executionStack.returnedValue()

        if (lastResult != null) {
            if (lastResult.dropResult) {
                executionStack.dropLastResult()
                continue
            }

            if (lastResult.stringSoFar.length in minLength..maxLength && lastResult.acceptableState) {
                break
            }
        }

        val frame = executionStack.currentFrame()
        if (frame == null) {
            break
        }

        // NOT ACCEPTED
        if (frame.transitions.size <= frame.selectedTransitions.size) {
            val result =
                ComputedSoFar(
                    frame.strMatch,
                    dropResult =
                        if (frame.state.isAccept) false else true,
                    acceptableState = frame.state.isAccept,
                )

            executionStack
                .returnValue(
                    result,
                )
            continue
        }

        frame.strMatch = lastResult?.stringSoFar ?: frame.strMatch

        if (frame.state.isAccept) {
            if (frame.strMatch.length == maxLength) {
                executionStack.returnValue(ComputedSoFar(frame.strMatch, acceptableState = frame.state.isAccept))
                continue
            }

            if (frame.strMatch.length > maxLength) {
                // NOT ACCEPTED
                executionStack.returnValue(ComputedSoFar(frame.strMatch, dropResult = true, acceptableState = frame.state.isAccept))
                continue
            }

            if (random.nextInt().toDouble() > 6.442450941E8 && frame.strMatch.length >= minLength) {
                executionStack.returnValue(ComputedSoFar(frame.strMatch, acceptableState = frame.state.isAccept))
                continue
            }
        } else {
            // NOT ACCEPTED
            if (frame.strMatch.length == maxLength) {
                executionStack.returnValue(ComputedSoFar(frame.strMatch, true, acceptableState = frame.state.isAccept))
                continue
            }
        }

        if (frame.transitions.isEmpty()) {
            executionStack.returnValue(ComputedSoFar(frame.strMatch, acceptableState = frame.state.isAccept))
            continue
        }

        val remainingTransitionsWithIndex = frame.transitions.mapIndexed { index, item -> index to item }.filter { (index, _) ->
            index !in frame.selectedTransitions
        }

        val nextRemainingTransition = random.nextInt(remainingTransitionsWithIndex.size)
        val nextInt = remainingTransitionsWithIndex[nextRemainingTransition].first

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
