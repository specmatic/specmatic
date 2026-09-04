package io.specmatic.core.pattern.regex

import java.util.ArrayDeque

internal class OpenApiRegexAnchorNormalizer {
    sealed interface Result {
        val regex: String
        data class Unchanged(override val regex: String) : Result
        data class Normalized(override val regex: String) : Result
        data class Unsupported(override val regex: String, val reason: String) : Result
    }

    fun normalize(regex: String): Result {
        if (!regex.containsAnchorAssertion()) return Result.Unchanged(regex)
        return when (val result = normalizeExpression(regex, Boundaries())) {
            is Normalization.Success -> Result.Normalized(result.regex)
            is Normalization.Unsupported -> Result.Unsupported(regex, result.reason)
        }
    }

    private fun normalizeExpression(expression: String, inheritedBoundaries: Boundaries): Normalization {
        val structure = when (val result = scan(expression)) {
            is ScanResult.Success -> result.structure
            is ScanResult.Invalid -> return Normalization.Unsupported(result.reason)
        }

        val alternatives = structure.splitTopLevelAlternatives(expression)
        val normalized = mutableListOf<String>()
        for (alternative in alternatives) {
            when (val result = normalizeAlternative(alternative, inheritedBoundaries)) {
                is Normalization.Success -> normalized += result.regex
                is Normalization.Unsupported -> return result
            }
        }

        return Normalization.Success(normalized.joinToString("|"))
    }

    private fun normalizeAlternative(alternative: String, inheritedBoundaries: Boundaries): Normalization {
        val boundaryAnchors = extractBoundaryAnchors(alternative)
        val boundaries = inheritedBoundaries.merge(boundaryAnchors.boundaries)
        val body = boundaryAnchors.body

        return when (val group = unwrapWholeGroup(body)) {
            is WholeGroup.Found -> {
                when (val result = normalizeExpression(group.body, boundaries)) {
                    is Normalization.Success -> Normalization.Success("${group.prefix}${result.regex})")
                    is Normalization.Unsupported -> result
                }
            }

            is WholeGroup.Unsupported -> {
                if (body.containsAnchorAssertion()) {
                    Normalization.Unsupported(group.reason)
                } else {
                    Normalization.Success(applyBoundaries(body, boundaries))
                }
            }

            WholeGroup.NotFound -> {
                if (body.containsAnchorAssertion()) {
                    Normalization.Unsupported("Anchor assertion occurs in a position that cannot be normalized safely: $alternative")
                } else {
                    Normalization.Success(applyBoundaries(body, boundaries))
                }
            }
        }
    }

    private fun applyBoundaries(body: String, boundaries: Boundaries): String {
        if (body.isEmpty()) {
            return if (boundaries.start && boundaries.end) EMPTY_STRING else ANY_STRING
        }

        return buildString {
            if (!boundaries.start) append(ANY_STRING)
            append(body)
            if (!boundaries.end) append(ANY_STRING)
        }
    }

    private fun extractBoundaryAnchors(value: String): BoundaryAnchors {
        var start = 0
        while (start < value.length && value[start] == '^') {
            start++
        }

        var end = value.length
        while (end > start && value[end - 1] == '$' && !value.isEscaped(end - 1)) {
            end--
        }

        return BoundaryAnchors(
            body = value.substring(start, end),
            boundaries = Boundaries(start = start > 0, end = end < value.length),
        )
    }

    private fun Structure.splitTopLevelAlternatives(value: String): List<String> {
        if (topLevelAlternations.isEmpty()) return listOf(value)

        val alternatives = mutableListOf<String>()
        var start = 0
        for (separator in topLevelAlternations) {
            alternatives += value.substring(start, separator)
            start = separator + 1
        }

        alternatives += value.substring(start)
        return alternatives
    }

    private fun unwrapWholeGroup(value: String): WholeGroup {
        if (!value.startsWith("(") || !value.endsWith(")")) {
            return WholeGroup.NotFound
        }

        val structure = when (val result = scan(value)) {
            is ScanResult.Success -> result.structure
            is ScanResult.Invalid -> return WholeGroup.Unsupported(result.reason)
        }

        if (structure.groupEnds[0] != value.lastIndex) {
            return WholeGroup.NotFound
        }

        val prefixLength = when {
            value.startsWith("(?:") -> 3
            value.startsWith("(?") -> return WholeGroup.Unsupported("Anchor normalization does not support this (?...) group construct")
            else -> 1
        }

        return WholeGroup.Found(
            prefix = value.substring(0, prefixLength),
            body = value.substring(prefixLength, value.lastIndex),
        )
    }

    private fun String.containsAnchorAssertion(): Boolean {
        var escaped = false
        var insideCharClass = false
        for (ch in this) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                insideCharClass -> if (ch == ']') insideCharClass = false
                ch == '[' -> insideCharClass = true
                ch == '^' || ch == '$' -> return true
            }
        }

        return false
    }

    private fun String.isEscaped(index: Int): Boolean {
        var backslashes = 0
        var current = index - 1
        while (current >= 0 && this[current] == '\\') {
            backslashes++
            current--
        }

        return backslashes % 2 != 0
    }

    private fun scan(value: String): ScanResult {
        val groupStarts = ArrayDeque<Int>()
        val groupEnds = mutableMapOf<Int, Int>()
        val topLevelAlternations = mutableListOf<Int>()

        var escaped = false
        var insideCharClass = false
        for (index in value.indices) {
            val ch = value[index]
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                insideCharClass -> if (ch == ']') insideCharClass = false
                ch == '[' -> insideCharClass = true
                ch == '(' -> groupStarts.addLast(index)
                ch == ')' -> {
                    if (groupStarts.isEmpty()) return ScanResult.Invalid("Unexpected ')' at index $index")
                    groupEnds[groupStarts.removeLast()] = index
                }
                ch == '|' && groupStarts.isEmpty() -> topLevelAlternations += index
            }
        }

        return when {
            escaped -> ScanResult.Invalid("Regex ends with an incomplete escape sequence")
            insideCharClass -> ScanResult.Invalid("Regex contains an unclosed character class")
            groupStarts.isNotEmpty() -> ScanResult.Invalid("Regex contains an unclosed group")
            else -> ScanResult.Success(Structure(topLevelAlternations = topLevelAlternations, groupEnds = groupEnds))
        }
    }

    private data class Boundaries(val start: Boolean = false, val end: Boolean = false) {
        fun merge(other: Boundaries): Boundaries = Boundaries(
            end = end || other.end,
            start = start || other.start,
        )
    }

    private data class BoundaryAnchors(val body: String, val boundaries: Boundaries)
    private data class Structure(val topLevelAlternations: List<Int>, val groupEnds: Map<Int, Int>)

    private sealed interface Normalization {
        data class Success(val regex: String) : Normalization
        data class Unsupported(val reason: String) : Normalization
    }

    private sealed interface ScanResult {
        data class Invalid(val reason: String) : ScanResult
        data class Success(val structure: Structure) : ScanResult
    }

    private sealed interface WholeGroup {
        data object NotFound : WholeGroup
        data class Unsupported(val reason: String) : WholeGroup
        data class Found(val prefix: String, val body: String) : WholeGroup
    }

    private companion object {
        // dk.brics uses () for the empty string and matches the complete input.
        // .* therefore represents the unconstrained side of an OpenAPI pattern match.
        const val ANY_STRING = ".*"
        const val EMPTY_STRING = "()"
    }
}
