package io.specmatic.test

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.Value

fun <T> Value.traverse(
    prefix: String = "", onScalar: (Value, String) -> Map<String, T>,
    onComposite: ((Value, String) -> Map<String, T>)? = null
): Map<String, T> {
    return when (this) {
        is JSONObjectValue -> this.traverse(prefix, onScalar, onComposite)
        is JSONArrayValue ->  this.traverse(prefix, onScalar, onComposite)
        is ScalarValue -> onScalar(this, prefix)
        else -> emptyMap()
    }.filterValues { it != null }
}

private fun <T> JSONObjectValue.traverse(
    prefix: String = "", onScalar: (Value, String) -> Map<String, T>,
    onComposite: ((Value, String) -> Map<String, T>)? = null
): Map<String, T> {
    return this.jsonObject.entries.flatMap { (key, value) ->
        val fullKey = if (prefix.isNotEmpty()) "$prefix.$key" else key
        value.traverse(fullKey, onScalar, onComposite).entries
    }.associate { it.toPair() } + onComposite?.invoke(this, prefix).orEmpty()
}

private fun <T> JSONArrayValue.traverse(
    prefix: String = "", onScalar: (Value, String) -> Map<String, T>,
    onComposite: ((Value, String) -> Map<String, T>)? = null
): Map<String, T> {
    return this.list.mapIndexed { index, value ->
        value.traverse("$prefix[$index]", onScalar, onComposite)
    }.flatMap { it.entries }.associate { it.toPair() } + onComposite?.invoke(this, prefix).orEmpty()
}
