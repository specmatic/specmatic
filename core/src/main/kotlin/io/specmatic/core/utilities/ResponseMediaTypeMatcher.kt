package io.specmatic.core.utilities

import io.ktor.http.ContentType

class ResponseMediaTypeMatcher(supportedFormats: Set<String> = configuredFormats()) {
    private val formats = supportedFormats
        .map { it.trim().lowercase() }
        .filter(String::isNotEmpty)
        .toSet()

    fun canCompareSchemas(previous: ContentType, current: ContentType): Boolean {
        if (!previous.hasValidWildcardSyntax() || !current.hasValidWildcardSyntax()) return false
        if (previous.hasSameTypeAndSubtype(current)) return true
        if (previous.hasWildcard() || current.hasWildcard()) return false
        return previous.hasSameTypeAs(current) && previous.hasSameSupportedFormatAs(current)
    }

    private fun ContentType.hasSameTypeAs(other: ContentType): Boolean {
        return contentType.equals(other.contentType, ignoreCase = true)
    }

    private fun ContentType.hasSameTypeAndSubtype(other: ContentType): Boolean {
        return hasSameTypeAs(other) && contentSubtype.equals(other.contentSubtype, ignoreCase = true)
    }

    private fun ContentType.hasSameSupportedFormatAs(other: ContentType): Boolean {
        val format = contentSubtype.supportedFormat() ?: return false
        return format == other.contentSubtype.supportedFormat()
    }

    private fun ContentType.hasWildcard(): Boolean = '*' in contentType || '*' in contentSubtype
    private fun ContentType.hasValidWildcardSyntax(): Boolean = when {
        contentType == "*" -> contentSubtype == "*"
        '*' in contentType -> false
        contentSubtype == "*" -> true
        '*' in contentSubtype -> false
        else -> true
    }

    private fun String.supportedFormat(): String? {
        val subtype = lowercase()
        val suffix = subtype.substringAfterLast('+', missingDelimiterValue = "")
        return when {
            subtype in formats -> subtype
            suffix in formats -> suffix
            else -> null
        }
    }

    companion object {
        const val FORMATS_PROPERTY = "specmatic.contentType.syntaxFormats"
        private val DEFAULT_FORMATS = setOf("json", "xml", "yaml")
        private fun configuredFormats(): Set<String> =
            Flags.getCommaSeparatedList(FORMATS_PROPERTY)
                ?.toSet()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_FORMATS
    }
}
