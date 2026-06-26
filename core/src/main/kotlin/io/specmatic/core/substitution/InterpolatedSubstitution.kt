package io.specmatic.core.substitution

import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.ContractException

internal object InterpolatedSubstitution {
    private val useRegex = Regex("\\$\\(([^()]+)\\)")
    private val captureRegex = Regex("\\(([^()]+:[^()]+)\\)")
    private val dollarFunctionBeforeParenRegex = Regex("""\$\w+\s*$""")

    fun containsLookup(value: String): Boolean {
        return useRegex.containsMatchIn(value)
    }

    fun resolve(value: String, resolveToken: (String) -> String): String {
        return useRegex.replace(value) { resolveToken(it.value) }
    }

    fun extractVariables(original: String, running: String): Map<String, String> {
        return when (val result = extractVariablesResult(original, running)) {
            is ExtractionResult.Success -> result.variables
            is ExtractionResult.Failure -> throw ContractException(result.message)
        }
    }

    private fun extractVariablesResult(original: String, running: String): ExtractionResult {
        val placeholders = captureRegex
            .findAll(original)
            .filterNot { isDollarFunctionCall(original, it.range.first) }
            .toList()

        if (placeholders.isEmpty()) return ExtractionResult.Success(emptyMap())
        if (hasAdjacentPlaceholders(placeholders)) {
            return ExtractionResult.Failure(
                message = "Ambiguous interpolation in \"$original\": adjacent placeholders need literal text between them"
            )
        }

        val regexSource = buildRegexSource(original, placeholders)
        val match = Regex(regexSource).matchEntire(running) ?: return ExtractionResult.Failure(
            message = "Could not extract substitution variables from \"$running\" using template \"$original\""
        )

        val variables = linkedMapOf<String, String>()
        placeholders.forEachIndexed { index, placeholder ->
            val name = placeholderTokenName(placeholder.value) ?: return ExtractionResult.Failure(
                message = "Invalid placeholder token \"${placeholder.value}\" in \"$original\""
            )

            val value = match.groups[index + 1]?.value ?: return ExtractionResult.Failure(
                message = "Could not extract placeholder \"$name\" from \"$running\" using template \"$original\""
            )

            val existingValue = variables[name]
            if (existingValue != null && existingValue != value) {
                return ExtractionResult.Failure(
                    message = "Conflicting extracted values for \"$name\" in \"$original\": \"$existingValue\" and \"$value\""
                )
            }

            variables[name] = value
        }

        return ExtractionResult.Success(variables)
    }

    private fun buildRegexSource(original: String, placeholders: List<MatchResult>): String {
        val regex = StringBuilder("^")
        var cursor = 0

        placeholders.forEach { placeholder ->
            if (cursor > placeholder.range.first) return "^$"
            regex.append(Regex.escape(original.substring(cursor, placeholder.range.first)))
            regex.append("(.+?)")
            cursor = placeholder.range.last + 1
        }

        regex.append(Regex.escape(original.substring(cursor)))
        regex.append("$")
        return regex.toString()
    }

    private fun placeholderTokenName(token: String): String? {
        if (!isPatternToken(token)) return null
        return token
            .removeSurrounding("(", ")")
            .split(":", limit = 2)
            .firstOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun hasAdjacentPlaceholders(placeholders: List<MatchResult>): Boolean {
        return placeholders.zipWithNext().any { (first, second) ->
            first.range.last + 1 == second.range.first
        }
    }

    private fun isDollarFunctionCall(value: String, openParenIndex: Int): Boolean {
        return dollarFunctionBeforeParenRegex.containsMatchIn(value.substring(0, openParenIndex))
    }

    private sealed interface ExtractionResult {
        data class Success(val variables: Map<String, String>) : ExtractionResult
        data class Failure(val message: String) : ExtractionResult
    }
}
