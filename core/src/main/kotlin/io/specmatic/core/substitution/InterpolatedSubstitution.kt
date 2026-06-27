package io.specmatic.core.substitution

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal object InterpolatedSubstitution {
    private val useRegex = Regex("\\$\\(([^()]+)\\)")
    private val captureRegex = Regex("\\(([^()]+:[^()]+)\\)")
    private val dollarFunctionBeforeParenRegex = Regex("""\$\w+\s*$""")

    fun containsLookup(value: String): Boolean {
        return useRegex.containsMatchIn(value)
    }

    fun resolve(value: String, resolveToken: (String) -> Value): Value {
        val entireMatch = useRegex.matchEntire(value)
        if (entireMatch != null) {
            return resolveToken(entireMatch.value)
        }

        return StringValue(useRegex.replace(value) {
            resolveToken(it.value).toUnformattedString()
        })
    }

    fun extractVariables(original: String, running: String, resolver: Resolver = Resolver()): Map<String, Value> {
        return when (val result = extractVariablesResult(original, running, resolver)) {
            is ExtractionResult.Success -> result.variables
            is ExtractionResult.Failure -> throw ContractException(result.message)
        }
    }

    private fun extractVariablesResult(original: String, running: String, resolver: Resolver): ExtractionResult {
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

        val variables = linkedMapOf<String, Value>()
        placeholders.forEachIndexed { index, placeholder ->
            val parts = placeholderParts(placeholder.value) ?: return ExtractionResult.Failure(
                message = "Invalid placeholder token \"${placeholder.value}\" in \"$original\""
            )

            val (name, typeName) = parts
            val value = match.groups[index + 1]?.value ?: return ExtractionResult.Failure(
                message = "Could not extract placeholder \"$name\" from \"$running\" using template \"$original\""
            )

            val existingValue = variables[name]
            if (existingValue != null && existingValue.toStringLiteral() != value) {
                return ExtractionResult.Failure(
                    message = "Conflicting extracted values for \"$name\" in \"$original\": \"${existingValue.toStringLiteral()}\" and \"$value\""
                )
            }

            variables[name] = parseExtractedValue(typeName, value, resolver)
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

    private fun parseExtractedValue(typeName: String, value: String, resolver: Resolver): Value {
        return runCatching {
            resolver.getPattern("($typeName)").parse(value, resolver)
        }.getOrDefault(StringValue(value))
    }

    private fun placeholderParts(token: String): Pair<String, String>? {
        if (!isPatternToken(token)) return null
        val pieces = token.removeSurrounding("(", ")").split(":", limit = 2)
        if (pieces.size != 2) return null

        val name = pieces[0].trim()
        val type = pieces[1].trim()
        if (name.isBlank() || type.isBlank()) return null

        return name to type
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
        data class Success(val variables: Map<String, Value>) : ExtractionResult
        data class Failure(val message: String) : ExtractionResult
    }
}
