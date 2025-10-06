package io.specmatic.core.pattern

import io.specmatic.core.log.logger
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

class RegExSpec(regex: String?) {
    private val originalRegex = regex
    private val generex = regex?.let(::cleanRegex)?.let(::InternalGenerex)

    fun validateRegex() {
        runCatching {
            if (generex == null || originalRegex == null) return
            val random = generex.random()
            if (!Regex(originalRegex).matches(random)) {
                logger.log("WARNING: Please check the regex $originalRegex. We generated a random string $random and the regex does not match the string.")
            }
        }.getOrElse { e ->
            throw IllegalArgumentException("Failed to parse regex $originalRegex\nReason: ${e.message}")
        }
    }

    fun validateMinLength(minLength: Int?) {
        if (generex == null) return
        minLength?.let {
            val shortestString = generex.generateShortest()
            if (it > shortestString.length && generex.isInfinite) {
                val longestString = generateLongestStringOrRandom(it)
                if (longestString.length < it) {
                    throw IllegalArgumentException("minLength $it cannot be greater than the length of longest possible string that matches regex ${this.originalRegex}")
                }
            }
        }
    }

    fun validateMaxLength(maxLength: Int?) {
        if (generex == null) return
        maxLength?.let {
            val shortestPossibleString = generex.generateShortest()
            if (shortestPossibleString.length > it) {
                throw IllegalArgumentException("maxLength $it cannot be less than the length of shortest possible string that matches regex ${this.originalRegex}")
            }
        }
    }

    fun generateShortestStringOrRandom(minLen: Int): String {
        if (generex == null) return randomString(minLen)
        val shortestExample = generex.generateShortest()
        if (minLen <= shortestExample.length) return shortestExample
        return generex.random(minLen, minLen)
    }

    fun negativeBasedOn(minLength: Int?, maxLength: Int?): Triple<String, Int?, Int?>? {
        if (generex == null) return null
        return Triple("${generex.regex}_", minLength, maxLength?.inc())
    }

    fun generateLongestStringOrRandom(maxLen: Int): String {
        if (generex == null) return randomString(maxLen)
        if (generex.isInfinite) return generex.random(maxLen, maxLen)
        return generex.generateLongest(maxLen) ?: throw IllegalStateException("No valid string found")
    }

    fun match(sampleData: StringValue) = originalRegex?.let {
        Regex(it).matches(sampleData.toStringLiteral())
    } ?: true

    fun generateRandomString(minLength: Int, maxLength: Int? = null): Value {
        return generex?.let {
            StringValue(generex.random(minLength, maxLength))
        } ?: StringValue(randomString(patternBaseLength(minLength, maxLength)))
    }

    private fun patternBaseLength(minLength: Int, maxLength: Int?): Int {
        return when {
            5 < minLength -> minLength
            maxLength != null && 5 > maxLength -> maxLength
            else -> 5
        }
    }

    private fun cleanRegex(regex: String): String {
        return regex
            .removePrefix("^")
            .removeSuffix("$")
            .removePrefix(WORD_BOUNDARY)
            .removeSuffix(WORD_BOUNDARY)
            .replaceRegexLowerBounds()
            .replaceShorthandCharacterClasses()
    }

    private fun String.replaceRegexLowerBounds(): String {
        val pattern = Regex("""\{,(\d+)}""")
        return this.replace(pattern) { matchResult -> "{0,${matchResult.groupValues[1]}}" }
    }

    private fun String.replaceShorthandCharacterClasses(): String {
        val charClassRegex = Regex("""\[(?:\\.|[^]])*]""")
        val replacements = mapOf(
            "\\w" to "a-zA-Z_0-9",
            "\\s" to " \\t\\n\\f\\r",
            "\\d" to "0-9",
            "\\D" to "^0-9",
            "\\S" to "^ \\t\\n\\f\\r",
            "\\W" to "^a-zA-Z_0-9"
        )

        val withCharClassesProcessed = charClassRegex.replace(this) { match ->
            val charClass = match.value
            val isNegated = charClass.startsWith("[^")
            val innerContent = replacements.entries.fold(
                initial = charClass.substring(if (isNegated) 2 else 1, charClass.length - 1)
            ) { acc, (key, value) ->
                acc.replace(key, value)
            }

            if (isNegated) "[^$innerContent]" else "[$innerContent]"
        }

        return replacements.entries.fold(withCharClassesProcessed) { acc, (key, value) ->
            acc.replace(key, "[$value]")
        }
    }

    override fun toString(): String {
        return originalRegex ?: "regex not set"
    }
}