package io.specmatic.core.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode

data class ConfigTemplateVariable(val startIndex: Int, val endIndex: Int, val names: Set<String>, val default: String, val fullText: String)
object ConfigTemplateUtils {
    internal val VARIABLE_TOKEN_REGEX = Regex("""\$?\{([^}:]+):([^}]*)}""")
    private val TEMPLATE_LIKE_TOKEN_REGEX = Regex("""\$?\{[^}]*}""")
    private const val MULTI_VARIABLE_SEPARATOR = "|"

    fun findVariableTokens(templateText: String): List<ConfigTemplateVariable> {
        return VARIABLE_TOKEN_REGEX.findAll(templateText).map { match ->
            val fullText = match.value
            val default = match.groupValues[2]
            val names = match.groupValues[1].split(MULTI_VARIABLE_SEPARATOR).filter(String::isNotBlank).toSet()
            ConfigTemplateVariable(match.range.first, match.range.last, names, default, fullText)
        }.toList()
    }

    fun isConfigTemplate(value: String): Boolean = VARIABLE_TOKEN_REGEX.containsMatchIn(value)

    fun validateTemplate(value: String): Result<Unit> = runCatching {
        val templateLikeTokens = TEMPLATE_LIKE_TOKEN_REGEX.findAll(value).map { it.value }.toList()
        require(templateLikeTokens.isNotEmpty()) { "no template token found" }

        val validTokenTexts = findVariableTokens(value).asSequence().filter { it.names.isNotEmpty() }.map { it.fullText }.toSet()
        val invalidTokens = templateLikeTokens.filter { it !in validTokenTexts }

        require(invalidTokens.isEmpty()) { "invalid token(s): ${invalidTokens.joinToString(", ")}" }
    }

    fun isFullTemplateToken(value: String): Boolean = VARIABLE_TOKEN_REGEX.matchEntire(value) != null

    fun resolveTemplateValue(value: String): String {
        val resolved = resolveTemplateValue(TextNode(value))
        return when {
            resolved.isTextual -> resolved.asText()
            else -> resolved.toString()
        }
    }

    fun resolveTemplateValue(value: JsonNode): JsonNode = resolveTemplates(value)

    fun createTemplate(names: Set<String>, default: String): String {
        val fullName = names.joinToString(MULTI_VARIABLE_SEPARATOR)
        return $$"${$$fullName:$$default}"
    }
}
