package io.specmatic.core.pattern

import dk.brics.automaton.RegExp
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.regex.OpenApiRegexAnchorNormalizer
import io.specmatic.core.pattern.regex.RegexBasedStringGenerator
import io.specmatic.core.pattern.regex.RegexLookaroundCompilation
import io.specmatic.core.pattern.regex.RegexLookaroundCompiler
import io.specmatic.core.pattern.regex.RegexScanner
import io.specmatic.core.pattern.regex.RegexToken
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal const val WORD_BOUNDARY = "\\b"
private const val GENERATABLE_WHITESPACE = " \t\n\u000B\u000C\r"
internal const val DOT_WITHOUT_LINE_TERMINATORS = "[^\n\r\u0085\u2028\u2029]"
private const val ECMASCRIPT_WHITESPACE = "$GENERATABLE_WHITESPACE\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

enum class RegexMatchMode {
    SEARCH,
    WHOLE_VALUE,
}

class RegExSpec(regex: String?, private val matchMode: RegexMatchMode = RegexMatchMode.SEARCH) {
    private val originalRegex = regex
    private val regexGenerator = regex?.let(::createRegexGenerator)
    private val regexForRuntimeMatch = originalRegex?.let { Regex(it.toRuntimeRegex(matchMode)) }

    init {
        validateRegex()
    }

    private fun createRegexGenerator(expression: String): RegexBasedStringGenerator {
        when (val result = RegexLookaroundCompiler(matchMode, ::translateToAutomatonSyntax).compile(expression)) {
            RegexLookaroundCompilation.NotApplicable -> return createRegularRegexGenerator(expression)
            is RegexLookaroundCompilation.Compiled -> return RegexBasedStringGenerator(expression, result.automaton)
            is RegexLookaroundCompilation.Unsupported -> {
                logger.log("WARNING: Cannot compile lookarounds in regex $expression: ${result.reason}. Using existing regex generation.")
            }
        }

        return RegexBasedStringGenerator(translateToAutomatonSyntax(prepareRegexForGeneration(expression)))
    }

    private fun createRegularRegexGenerator(expression: String): RegexBasedStringGenerator {
        val preparedRegex = prepareRegexForGeneration(expression)
        val semanticRegex = translateToAutomatonSyntax(preparedRegex)
        val readableRegex = translateToAutomatonSyntax(preparedRegex.withReadableCharacterChoices())
        return if (readableRegex == semanticRegex) RegexBasedStringGenerator(semanticRegex)
        else RegexBasedStringGenerator(semanticRegex, RegExp(readableRegex, 0).toAutomaton(), diagnosticRegex = expression)
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
        val generator = regexGenerator ?: return randomString(minLen)
        val shortestExample = generator.generateShortest()
        if (minLen <= shortestExample.length) return shortestExample
        return generator.random(minLen, minLen)
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
        val generator = regexGenerator ?: return randomString(maxLen)
        if (generator.isInfinite) return generator.random(maxLen, maxLen)
        return generator.generateLongest(maxLen) ?: throw IllegalStateException("No valid string found")
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
            StringValue(it.random(minLength, maxLength))
        } ?: StringValue(randomString(patternBaseLength(minLength, maxLength)))
    }

    private fun patternBaseLength(minLength: Int, maxLength: Int?): Int {
        return when {
            5 < minLength -> minLength
            maxLength != null && 5 > maxLength -> maxLength
            else -> 5
        }
    }

    private fun prepareRegexForGeneration(regex: String): String {
        return regex
            .requote()
            .removeOuterAnchors()
            .removeOuterWordBoundaries()
            .replaceRegexLowerBounds()
    }

    private fun String.removeOuterWordBoundaries(): String {
        val withoutStartBoundary = removePrefix(WORD_BOUNDARY)
        val trailingBackslashes = withoutStartBoundary.dropLast(1).takeLastWhile { it == '\\' }.length
        return if (withoutStartBoundary.endsWith(WORD_BOUNDARY) && trailingBackslashes % 2 == 1) {
            withoutStartBoundary.dropLast(WORD_BOUNDARY.length)
        } else withoutStartBoundary
    }

    internal fun translateToAutomatonSyntax(regex: String): String {
        return regex
            .replaceRegexLowerBounds()
            .replaceShorthandCharacterClasses()
            .requote()
            .normalizeRegularTokens()
            .replaceUnescapedDot()
    }

    private fun String.withReadableCharacterChoices(): String {
        val preferredShorthands = mapOf(
            'S' to "[A-Za-z0-9]",
            'D' to "[A-Za-z]",
            'W' to """[!-/:-@\[-\^`{-~]""",
            's' to "[ ]",
        )
        val result = StringBuilder(length)
        var insideCharClass = false
        var index = 0

        while (index < length) {
            val ch = this[index]
            when {
                ch == '\\' && index + 1 < length -> {
                    val escaped = this[index + 1]
                    val replacement = preferredShorthands[escaped].takeUnless { insideCharClass }
                    result.append(replacement ?: substring(index, index + 2))
                    index += 2
                }
                else -> {
                    when (ch) {
                        '[' -> insideCharClass = true
                        ']' -> insideCharClass = false
                    }
                    result.append(if (ch == '.' && !insideCharClass) "[A-Za-z0-9]" else ch.toString())
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun String.removeOuterAnchors(): String {
        val result = OpenApiRegexAnchorNormalizer(allowUnanchoredSides = matchMode == RegexMatchMode.SEARCH).normalize(this)
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
            "\\s" to GENERATABLE_WHITESPACE,
            "\\S" to "^$ECMASCRIPT_WHITESPACE"
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

    private fun String.normalizeRegularTokens(): String {
        return RegexScanner().scan(this).tokens.joinToString("") { token ->
            val text = token.text(this)
            when (token) {
                is RegexToken.GroupStart.Named -> "("
                is RegexToken.GroupStart.Regular -> if (text == "(?:") "(" else text
                // Preference modifiers do not change repetition counts: a+? -> a+; literal question marks stay intact.
                is RegexToken.Quantifier -> if (text.length > 1 && text.endsWith('?')) text.dropLast(1) else text
                else -> text
            }
        }
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
