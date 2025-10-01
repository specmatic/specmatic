package io.specmatic.core.pattern

import com.mifmif.common.regex.Generex
import dk.brics.automaton.RegExp
import dk.brics.automaton.State
import io.specmatic.core.log.logger
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import kotlin.collections.MutableMap
import kotlin.collections.forEach
import kotlin.collections.mutableMapOf
import kotlin.collections.set

internal const val WORD_BOUNDARY = "\\b"

class RegExSpec(regex: String?) {
    private val originalRegex = regex
    private val regex = regex?.let {
        doesNotStartWithRegexDelimiters(it)
        .replaceRegexLowerBounds()
        .removePrefix("^").removeSuffix("$")
        .removePrefix(WORD_BOUNDARY).removeSuffix(WORD_BOUNDARY)
        .replaceShorthandEscapeSeq()
        .validateRegex()
    }

    private fun doesNotStartWithRegexDelimiters(regex: String): String {
        if (regex.startsWith("/") && regex.endsWith("/")) {
            throw IllegalArgumentException("Invalid regex $originalRegex. OpenAPI follows ECMA-262 regular expressions, which do not support / / delimiters like those used in many programming languages")
        }
        return regex
    }

    private val isFinite = this.regex != null && !Generex(this.regex).isInfinite

    fun negativeBasedOn(): String {
        return "${regex}_"
    }

    fun validateMinLength(minLength: Int?) {
        if (regex == null) return
        minLength?.let {
            val shortestString = generateShortestString()
            if (it > shortestString.length && isFinite) {
                val longestString = generateLongestStringOrRandom(it)
                if (longestString.length < it) {
                    throw IllegalArgumentException("minLength $it cannot be greater than the length of longest possible string that matches regex ${this.originalRegex}")
                }
            }
        }
    }

    fun validateMaxLength(maxLength: Int?) {
        if (regex == null) return
        maxLength?.let {
            val shortestPossibleString = generateShortestString()
            if (shortestPossibleString.length > it) {
                throw IllegalArgumentException("maxLength $it cannot be less than the length of shortest possible string that matches regex ${this.originalRegex}")
            }
        }
    }

    fun validateLength(length: Int) {
        if (regex == null) return
        generateFromRegex(regex, length, length)
    }

    fun generateShortestStringOrRandom(minLen: Int): String {
        if (regex == null) return randomString(minLen)
        val shortestExample = generateShortestString()
        if (minLen <= shortestExample.length) return shortestExample
        return Generex(regex).randomWithRetryOnStackOverflow(minLen, minLen)
    }

    private fun generateShortestString(): String =
        regex?.let { RegExp(it).toAutomaton().getShortestExample(true) } ?: ""

    fun generateLongestStringOrRandom(maxLen: Int): String {
        if (regex == null) return randomString(maxLen)

        val generex = Generex(regex)
        if (generex.isInfinite) {
            return generex.randomWithRetryOnStackOverflow(maxLen, maxLen)
        }

        val automaton = RegExp(regex).toAutomaton()
        return longestFrom(automaton.initialState, maxLen, mutableMapOf())
            ?: throw IllegalStateException("No valid string found")
    }

    fun match(sampleData: StringValue) =
        regex?.let { Regex(it).matches(sampleData.toStringLiteral()) } ?: true

    private fun String.replaceRegexLowerBounds(): String {
        val pattern = Regex("""\{,(\d+)}""")
        return this.replace(pattern) { matchResult ->
            "{0,${matchResult.groupValues[1]}}"
        }
    }

    private fun String.validateRegex(): String {
        return runCatching {
            val random = Generex(this).randomWithRetryOnStackOverflow()
            if (!Regex(this).matches(random)) {
                logger.log("WARNING: Please check the regex $originalRegex. We generated a random string $random and the regex does not match the string.")
            }
            this
        }.getOrElse {
                e -> throw IllegalArgumentException("Failed to parse regex $originalRegex\nReason: ${e.message}")
        }
    }

    private fun String.replaceShorthandEscapeSeq(): String {
        // This regex matches a character class: a '[' followed by either an escaped char (e.g. \])
        // or any char that is not a ']', until the first unescaped ']'
        val charClassRegex = Regex("""\[(?:\\.|[^\]])*]""")

        return charClassRegex.replace(this) { matchResult ->
            val charClass = matchResult.value

            // Check if the class is negated (i.e. starts with "[^")
            val isNegated = charClass.startsWith("[^")
            // Extract the inner content (skip the starting '[' or "[^" and the ending ']')
            val innerContent = if (isNegated)
                charClass.substring(2, charClass.length - 1)
            else
                charClass.substring(1, charClass.length - 1)

            // Replace shorthand escapes inside the character class.
            // Note: The replacements are applied only to the inner content.
            val replaced = innerContent
                .replace(Regex("""\\w"""), """a-zA-Z0-9_""")
                .replace(Regex("""\\s"""), """ \\t\\r\\n""")
                .replace(Regex("""\\d"""), """0-9""")

            // Rebuild the character class with its original negation if present.
            if (isNegated) "[^$replaced]" else "[$replaced]"
        }
    }

    /**
     * Recursively computes the longest accepted string (using at most [remaining] transitions)
     * from [state]. Returns null if no accepted string can be formed within the given limit.
     *
     * The tie-breaker when strings have the same length is the lexicographical order.
     */
    private fun longestFrom(state: State, remaining: Int, memo: MutableMap<Pair<State, Int>, String?>): String? {
        val key = state to remaining
        memo[key]?.let { return it }

        var best: String? = if (state.isAccept) "" else null

        if (remaining > 0) {
            state.transitions.forEach { t ->
                val sub = longestFrom(t.dest, remaining - 1, memo)
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

    fun generateRandomString(minLength: Int, maxLength: Int? = null): Value {
        return regex?.let {
            StringValue(generateFromRegex(it, minLength, maxLength))
        } ?: StringValue(randomString(patternBaseLength(minLength, maxLength)))
    }

    private fun patternBaseLength(minLength: Int, maxLength: Int?): Int {
        return when {
            5 < minLength -> minLength
            maxLength != null && 5 > maxLength -> maxLength
            else -> 5
        }
    }

    fun generateFromRegex(regex: String, minLength: Int, maxLength: Int? = null): String {
        return Generex(regex).randomWithRetryOnStackOverflow(minLength, maxLength)
    }

    override fun toString(): String {
        return regex ?: "regex not set"
    }

    //TODO: This is a workaround for a bug in Generex. Remove this when the bug is fixed.
    private fun Generex.randomWithRetryOnStackOverflow(minLen: Int = 1, maxLength: Int? = null, remainingRetries: Int = 5): String {
        return try {
            this.random(minLen, maxLength ?: Int.MAX_VALUE)
        } catch (e: StackOverflowError) {
            if (remainingRetries > 0) {
                logger.debug("StackOverflowError while generating for Regex: $regex, minLen: $minLen, maxLength: $maxLength, retrying...")
                this.randomWithRetryOnStackOverflow(minLen, maxLength, remainingRetries - 1)
            } else {
                throw ContractException(
                    errorMessage = "StackOverflowError while generating for Regex: $regex, minLen: $minLen, maxLength: $maxLength",
                    breadCrumb = regex.orEmpty(),
                    exceptionCause = e
                )
            }
        }
    }
}