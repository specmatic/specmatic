package io.specmatic.core.utilities

import io.specmatic.core.HttpPathPattern
import io.specmatic.core.pathToPattern
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.withoutPatternDelimiters

data class OpenApiPath(private val parts: List<String>) {
    fun normalize(): OpenApiPath {
        val numericIndices = parts.withIndex().filter { isNumericPathSegment(it.value) || isDecimal(it.value) }.map { it.index }
        val renamed = parts.mapIndexed { idx, segment ->
            val type = numericType(segment) ?: return@mapIndexed segment
            when {
                numericIndices.size == 1 -> "(param:$type)"
                else -> {
                    val position = numericIndices.indexOf(idx) + 1
                    "(param$position:$type)"
                }
            }
        }

        return this.copy(parts = renamed)
    }

    fun toPath(): String {
        return parts.joinToString(separator = PATH_SEPARATOR, prefix = PATH_SEPARATOR) {
            if (!isPathParameter(it)) return@joinToString it
            "{${withoutPatternDelimiters(it).substringBefore(":")}}"
        }
    }

    fun toHttpPathPattern(): HttpPathPattern {
        return toHttpPathPattern("")
    }

    fun toHttpPathPattern(pathIfBlank: String): HttpPathPattern {
        val finalPath = parts.joinToString(separator = PATH_SEPARATOR, prefix = PATH_SEPARATOR).let {
            it.ifBlank { pathIfBlank }
        }
        return HttpPathPattern(pathSegmentPatterns = pathToPattern(finalPath), path = finalPath)
    }

    private fun numericType(segment: String): String? = when {
        isNumericPathSegment(segment) -> "integer"
        isDecimal(segment) -> "number"
        else -> null
    }

    private fun isNumericPathSegment(segment: String): Boolean = segment.isNotEmpty() && segment.all(Char::isDigit)

    private fun isDecimal(segment: String): Boolean = segment.count { it == '.' } == 1 && segment.replace(".", "").all(Char::isDigit)

    private fun isPathParameter(value: String): Boolean = isPatternToken(value) && value.contains(":")

    companion object {
        private const val PATH_SEPARATOR = "/"

        fun from(path: String, escape: Boolean = true): OpenApiPath {
            val escaped = if (escape) path.replace('{', '_').replace('}', '_') else path
            return OpenApiPath(escaped.split(PATH_SEPARATOR).filter(String::isNotBlank))
        }
    }
}
