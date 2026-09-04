package io.specmatic.core.pattern

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.regex.OpenApiRegexAnchorNormalizer
import io.specmatic.core.pattern.regex.RegexBasedStringGenerator
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal const val WORD_BOUNDARY = "\\b"
internal const val DOT_WITHOUT_LINE_TERMINATORS = "[^\n\r\u0085\u2028\u2029]"

enum class RegexMatchMode {
    SEARCH,
    WHOLE_VALUE,
}

class RegExSpec(regex: String?, private val matchMode: RegexMatchMode = RegexMatchMode.SEARCH) {
    private val originalRegex = regex
    private val regexGenerator = regex?.let(::cleanRegex)?.let(::RegexBasedStringGenerator)
    private val regexForRuntimeMatch = originalRegex?.let { Regex(it.toRuntimeRegex(matchMode)) }

    init {
        validateRegex()
    }

    private fun validateRegex() {
        runCatching {
            if (regexGenerator == null || regexForRuntimeMatch == null) return
            val random = regexGenerator.random()
            if (!matchesRuntimeRegex(random)) {
                logger.log("WARNING: Please check the regex $originalRegex. We generated a random string $random and the regex does not match the string.")
            }
        }.getOrElse { e ->
            throw IllegalArgumentException("Failed to parse regex $originalRegex\nReason: ${e.message}")
        }
    }

    fun validateMinLength(minLength: Int?) {
        if (regexGenerator == null) return
        minLength?.let {
            val shortestString = regexGenerator.generateShortest()
            if (it > shortestString.length && regexGenerator.isFinite) {
                val longestString = generateLongestStringOrRandom(it)
                if (longestString.length < it) {
                    throw IllegalArgumentException("minLength $it cannot be greater than the length of longest possible string that matches regex ${this.originalRegex}")
                }
            }
        }
    }

    fun validateMaxLength(maxLength: Int?) {
        if (regexGenerator == null) return
        maxLength?.let {
            val shortestPossibleString = regexGenerator.generateShortest()
            if (shortestPossibleString.length > it) {
                throw IllegalArgumentException("maxLength $it cannot be less than the length of shortest possible string that matches regex ${this.originalRegex}")
            }
        }
    }

    fun generateShortestStringOrRandom(minLen: Int): String {
        if (regexGenerator == null) return randomString(minLen)
        val shortestExample = regexGenerator.generateShortest()
        if (minLen <= shortestExample.length) return shortestExample
        return regexGenerator.random(minLen, minLen)
    }

    fun negativeBasedOn(minLength: Int?, maxLength: Int?): Triple<String, Int?, Int?>? {
        if (originalRegex == null) return null
        val negativeRegex = if (originalRegex.endsWith("$")) {
            originalRegex.dropLast(1) + "_$"
        } else {
            originalRegex + "_"
        }
        return Triple(negativeRegex, minLength, maxLength?.inc())
    }

    fun generateLongestStringOrRandom(maxLen: Int): String {
        if (regexGenerator == null) return randomString(maxLen)
        if (regexGenerator.isInfinite) return regexGenerator.random(maxLen, maxLen)
        return regexGenerator.generateLongest(maxLen) ?: throw IllegalStateException("No valid string found")
    }

    fun match(sampleData: StringValue) = matchesRuntimeRegex(sampleData.toStringLiteral())
    private fun matchesRuntimeRegex(value: String): Boolean {
        return when {
            regexForRuntimeMatch == null -> true
            matchMode == RegexMatchMode.SEARCH -> regexForRuntimeMatch.containsMatchIn(value)
            else -> regexForRuntimeMatch.matches(value)
        }
    }

    fun generateRandomString(minLength: Int, maxLength: Int? = null): Value {
        return regexGenerator?.let {
            StringValue(regexGenerator.random(minLength, maxLength))
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
            .removeOuterAnchors()
            .removePrefix(WORD_BOUNDARY)
            .removeSuffix(WORD_BOUNDARY)
            .replaceRegexLowerBounds()
            .replaceShorthandCharacterClasses()
            .requote()
            .replaceNonCapturingGroups()
            .replaceUnescapedDot()
    }

    private fun String.removeOuterAnchors(): String {
        val result = OpenApiRegexAnchorNormalizer().normalize(this)
        if (result is OpenApiRegexAnchorNormalizer.Result.Unsupported) {
            logger.debug("Could not normalize OpenAPI regex anchors: ${result.reason}. Using the original regex: ${result.regex}")
        }

        return result.regex
    }

    private fun String.replaceUnescapedDot(): String {
        val result = StringBuilder(length)
        var insideCharClass = false
        var pendingEscape = false

        for (ch in this) {
            when {
                pendingEscape -> {
                    result.append(ch)
                    pendingEscape = false
                }
                ch == '\\' -> {
                    pendingEscape = true
                    result.append(ch)
                }
                ch == '[' -> {
                    insideCharClass = true
                    result.append(ch)
                }
                ch == ']' -> {
                    insideCharClass = false
                    result.append(ch)
                }
                !insideCharClass && ch == '.' -> {
                    result.append(DOT_WITHOUT_LINE_TERMINATORS)
                }
                else -> result.append(ch)
            }
        }

        if (pendingEscape) result.append('\\')
        return result.toString()
    }

    private fun String.toRuntimeRegex(matchMode: RegexMatchMode): String {
        return replaceRegexLowerBounds().let { regex ->
            when (matchMode) {
                RegexMatchMode.WHOLE_VALUE -> regex
                RegexMatchMode.SEARCH -> regex.replaceEcmaEndAssertions()
            }
        }
    }

    private fun String.replaceRegexLowerBounds(): String {
        val pattern = Regex("""\{,(\d+)}""")
        return this.replace(pattern) { matchResult -> "{0,${matchResult.groupValues[1]}}" }
    }

    private fun String.replaceShorthandCharacterClasses(): String {
        val shorthandMap = mapOf(
            "\\w" to "a-zA-Z_0-9",
            "\\W" to "^a-zA-Z_0-9",
            "\\d" to "0-9",
            "\\D" to "^0-9",
            "\\s" to " \t\n\u000c\r",
            "\\S" to "^ \t\n\u000c\r"
        )

        val result = StringBuilder(length)
        var insideCharClass = false
        var pendingEscape = false

        for (ch in this) {
            when {
                pendingEscape -> {
                    val seq = "\\$ch"
                    shorthandMap[seq]?.let { replacement ->
                        if (insideCharClass) {
                            result.append(replacement)
                        } else {
                            result.append("[").append(replacement).append("]")
                        }
                    } ?: result.append(seq)
                    pendingEscape = false
                }
                ch == '\\' -> {
                    pendingEscape = true
                }
                ch == '[' -> {
                    insideCharClass = true
                    result.append(ch)
                }
                ch == ']' -> {
                    insideCharClass = false
                    result.append(ch)
                }
                else -> result.append(ch)
            }
        }

        if (pendingEscape) result.append('\\')
        return result.toString()
    }

    private fun String.requote(): String {
        val patternRequoted = Regex("""\\Q(.*?)\\E""")
        val patternSpecial = Regex("[.^$*+?(){|\\[@\\\\]")
        return patternRequoted.replace(this) { matchResult ->
            val group = matchResult.groups[1]?.value.orEmpty()
            patternSpecial.replace(group) { "\\${it.value}" }
        }
    }

    private fun String.replaceNonCapturingGroups(): String {
        val result = StringBuilder(length)
        var insideCharClass = false
        var pendingEscape = false
        var index = 0

        while (index < length) {
            val ch = this[index]
            when {
                pendingEscape -> {
                    result.append(ch)
                    pendingEscape = false
                    index++
                }
                ch == '\\' -> {
                    pendingEscape = true
                    result.append(ch)
                    index++
                }
                ch == '[' -> {
                    insideCharClass = true
                    result.append(ch)
                    index++
                }
                ch == ']' -> {
                    insideCharClass = false
                    result.append(ch)
                    index++
                }
                !insideCharClass && ch == '(' && index + 2 < length && this[index + 1] == '?' && this[index + 2] == ':' -> {
                    result.append('(')
                    index += 3
                }
                else -> {
                    result.append(ch)
                    index++
                }
            }
        }

        return result.toString()
    }

    private fun String.replaceEcmaEndAssertions(): String {
        val result = StringBuilder(length)

        var escaped = false
        var insideCharClass = false
        for (ch in this) {
            when {
                escaped -> {
                    result.append(ch)
                    escaped = false
                }
                ch == '\\' -> {
                    result.append(ch)
                    escaped = true
                }
                ch == '[' -> {
                    result.append(ch)
                    insideCharClass = true
                }
                ch == ']' && insideCharClass -> {
                    result.append(ch)
                    insideCharClass = false
                }
                ch == '$' && !insideCharClass -> result.append("\\z")
                else -> result.append(ch)
            }
        }

        return result.toString()
    }

    override fun toString(): String {
        return regexGenerator?.regex ?: "regex not set"
    }
}
