package io.specmatic.core

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value

internal fun JSONObjectValue.insert(path: QueryObjectPath, value: Value): Value {
    return insertAt(tokens = path.tokens, value = value)
}

private fun Value.insertAt(tokens: List<QueryObjectPathToken>, value: Value): Value {
    if (tokens.isEmpty()) return value

    return when (val token = tokens.first()) {
        is QueryObjectPathToken.Property -> {
            val currentObject = this as? JSONObjectValue ?: JSONObjectValue()
            val updatedPropertyValue = currentObject.jsonObject[token.name]
                ?.insertAt(tokens.drop(1), value)
                ?: JSONObjectValue().insertAt(tokens.drop(1), value)

            JSONObjectValue(currentObject.jsonObject + (token.name to updatedPropertyValue))
        }
        is QueryObjectPathToken.Index -> {
            val currentArray = this as? JSONArrayValue ?: JSONArrayValue()
            val paddedValues = currentArray.list.padToIndex(token.index)
            val updatedItemValue = paddedValues[token.index].insertAt(tokens.drop(1), value)

            JSONArrayValue(paddedValues.updatedAt(token.index, updatedItemValue))
        }
    }
}

private fun List<Value>.padToIndex(index: Int): List<Value> {
    return this + List((index + 1 - size).coerceAtLeast(0)) { NullValue }
}

private fun List<Value>.updatedAt(index: Int, value: Value): List<Value> {
    return mapIndexed { itemIndex, itemValue ->
        if (itemIndex == index) value else itemValue
    }
}
