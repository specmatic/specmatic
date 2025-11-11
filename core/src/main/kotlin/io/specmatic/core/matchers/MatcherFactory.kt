package io.specmatic.core.matchers

import io.specmatic.core.BreadCrumb
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.utilities.fromYamlProperties
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

interface MatcherFactory {
    fun parse(path: BreadCrumb, value: Value, context: MatcherContext): ReturnValue<out Matcher>

    fun canParseFrom(path: BreadCrumb, properties: Map<String, Value>): Boolean

    fun parseFrom(path: BreadCrumb, properties: Map<String, Value>, context: MatcherContext): ReturnValue<out Matcher>

    fun extractPropertiesIfExist(value: Value): Map<String, Value>? {
        if (value !is StringValue) return null
        val hasColonPattern = value.nativeValue.contains(COLON_SEPARATED_PROPERTIES)
        val hasMultiplePairs = value.nativeValue.count { it == ',' } > 0 || value.nativeValue.count { it == ':' } > 0
        if (!hasColonPattern || !hasMultiplePairs) return null
        return fromYamlProperties(value.nativeValue).takeUnless(Map<String, Value>::isEmpty)
    }

    companion object {
        private val COLON_SEPARATED_PROPERTIES = Regex("""\w+\s*:\s*\S+""")
    }
}
