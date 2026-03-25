package io.specmatic.test.variables

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.test.ExampleProcessor.toFactStore

// Matches a string that is exactly one substitution token, e.g. `$(FIXTURE.BEFORE.1.RESPONSE.id)`.
private val EXACT_SUBSTITUTION_PATTERN = Regex("""^\$\((.*)\)$""")
// Matches substitution tokens embedded inside a larger string, e.g. `/orders/$(FIXTURE.BEFORE.1.RESPONSE.id)`.
private val INLINE_SUBSTITUTION_PATTERN = Regex("""\$\((.*?)\)""")

/**
 * Stores values captured during a single execution flow and resolves `$(...)` lookups against them.
 * Stored values are flattened so nested JSON fields can be referenced using dotted paths.
 */
class ExecutionVariableContext {
    private val values = linkedMapOf<String, Value>()

    fun store(key: String, value: Value) {
        values[key] = value
        values.putAll(value.toFactStore(key))
    }

    fun getValue(key: String): Value =
        values[key] ?: throw ContractException(
            breadCrumb = key,
            errorMessage = "Could not resolve \"$key\", key does not exist in execution variable context"
        )

    fun allValues(): Map<String, Value> = values.toMap()

    fun resolveString(value: String): String =
        resolveSubstitutions(
            value = value,
            convertExactMatchValue = Value::toStringLiteral,
            convertInterpolatedString = { it }
        )

    fun resolveValue(value: Value): Value =
        when (value) {
            is StringValue -> resolveStringValue(value)
            is JSONObjectValue -> JSONObjectValue(value.jsonObject.mapValues { (_, childValue) -> resolveValue(childValue) })
            is JSONArrayValue -> JSONArrayValue(value.list.map(::resolveValue))
            else -> value
        }

    fun resolveAny(value: Any?): Any? =
        when (value) {
            is String -> resolveStringOrNativeValue(value)
            is Map<*, *> -> value.entries.associate { (key, childValue) ->
                key.toString() to resolveAny(childValue)
            }
            is List<*> -> value.map(::resolveAny)
            else -> value
        }

    private fun resolveStringValue(value: StringValue): Value =
        resolveSubstitutions(
            value = value.string,
            convertExactMatchValue = { it },
            convertInterpolatedString = ::StringValue
        )

    private fun resolveStringOrNativeValue(value: String): Any? =
        resolveSubstitutions(
            value = value,
            convertExactMatchValue = Value::toNativeValue,
            convertInterpolatedString = { it }
        )

    private fun <T> resolveSubstitutions(
        value: String,
        convertExactMatchValue: (Value) -> T,
        convertInterpolatedString: (String) -> T
    ): T {
        lookupExactValue(value)?.let { return convertExactMatchValue(it) }

        val resolved = INLINE_SUBSTITUTION_PATTERN.replace(value) { match ->
            getValue(match.groupValues[1]).toStringLiteral()
        }

        return convertInterpolatedString(resolved)
    }

    private fun lookupExactValue(value: String): Value? =
        EXACT_SUBSTITUTION_PATTERN.matchEntire(value)?.groupValues?.get(1)?.let(::getValue)
}
