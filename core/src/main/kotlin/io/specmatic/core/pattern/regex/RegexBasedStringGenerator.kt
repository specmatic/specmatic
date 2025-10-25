package io.specmatic.core.pattern.regex

import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import io.specmatic.conversions.REASONABLE_STRING_LENGTH
import io.specmatic.core.log.logger
import kotlin.random.Random
import kotlin.random.asJavaRandom

class RegexBasedStringGenerator(
    val regex: String,
) {
    val isInfinite: Boolean
        get() {
            return !isFinite
        }
    val isFinite: Boolean
        get() {
            return RegExp(regex).toAutomaton().isFinite
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
        val state = RegExp(regex, 0).toAutomaton().initialState
        val executionStack =
            ExecutionStack(Stage(StringBuilder(), state))
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
        var answer: String? = null

        while (answer == null) {
            val stage = executionStack.lastStage() ?: break

            when (val result = stage.computeNext(minLength, maxLength)) {
                is Answer -> answer = result.answer
                ComputationPathIsLostCause -> executionStack.backtrack()
                is NextStage -> executionStack.addStage(result.stage)
            }
        }

        if (answer == null) {
            val warning = warningMessage(minLength, maxLength)

            logger.log(warning.toString().trim())
            return ""
        }

        return answer
    }

    fun warningMessage(minLength: Int, maxLength: Int): StringBuilder {
        val constraints = mutableListOf<String>()

        if (minLength > 0) {
            constraints.add("minLength $minLength")
        }

        if (maxLength < REASONABLE_STRING_LENGTH) {
            constraints.add("maxLength $maxLength")
        }

        val warning = StringBuilder("WARNING: Could not generate a string based on $regex")

        if (constraints.isNotEmpty()) {
            warning.append(" with ")
            warning.append(
                constraints.joinToString(
                    ", ",
                ),
            )
        }
        return warning
    }
}
