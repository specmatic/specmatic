package io.specmatic.core.matchers

import io.specmatic.core.utilities.yamlStringToValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

// Splits on commas that are followed by a new `key:` token, so commas inside values (e.g. regex patterns) are preserved.
private val MATCHER_PROPERTY_SEPARATOR = Regex(""",\s*(?=[A-Za-z_][A-Za-z0-9_-]*\s*:)""")

internal fun parseMatcherTokenProperties(value: String): Map<String, Value> {
    if (value.isBlank()) return emptyMap()

    return value
        .trim()
        .split(MATCHER_PROPERTY_SEPARATOR)
        .associate(::toMatcherPropertyPair)
}

private fun toMatcherPropertyPair(rawProperty: String): Pair<String, Value> {
    val separatorIndex = rawProperty.indexOf(':')
    if (separatorIndex < 0) throw IllegalArgumentException("Invalid matcher token property '$rawProperty'")

    val key = rawProperty.substring(0, separatorIndex).trim()
    if (key.isBlank()) throw IllegalArgumentException("Invalid matcher token property '$rawProperty'")

    val rawValue = rawProperty.substring(separatorIndex + 1).trim()
    return key to parseMatcherPropertyValue(key, rawValue)
}

private fun parseMatcherPropertyValue(key: String, rawValue: String): Value {
    if (key == RegexMatcher.PATTERN_PROPERTY_KEY) {
        return StringValue(rawValue)
    }

    val parsed = yamlStringToValue("value: $rawValue")
    return if (parsed is JSONObjectValue) parsed.jsonObject["value"] ?: StringValue(rawValue) else StringValue(rawValue)
}
