package io.specmatic.core

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal fun serializeNestedObjectQueryValues(
    valuesMap: Map<String, Value>,
    nestedObjectQueryParams: List<NestedObjectQueryParam>
): List<Pair<String, String>> {
    val nestedParameterNames = nestedObjectQueryParams.map { it.parameterName }.toSet()
    val nestedPairs = nestedObjectQueryParams.flatMap { nestedQueryParam ->
        val value = valuesMap[nestedQueryParam.parameterName] as? JSONObjectValue
            ?: return@flatMap emptyList()

        value.toQueryParamPairs(nestedQueryParam.parameterName, nestedQueryParam.syntax)
    }

    val ordinaryPairs = valuesMap
        .filterKeys { it !in nestedParameterNames }
        .map { (key, value) -> key to value.toStringLiteral() }

    return ordinaryPairs + nestedPairs
}

internal fun serializeNestedObjectQueryValue(
    parameterName: String,
    value: JSONObjectValue,
    nestedObjectQueryParams: List<NestedObjectQueryParam>
): List<Pair<String, String>>? {
    val nestedQueryParam = nestedObjectQueryParams.firstOrNull { it.parameterName == parameterName } ?: return null
    return value.toQueryParamPairs(parameterName, nestedQueryParam.syntax)
}

private fun JSONObjectValue.toQueryParamPairs(parameterName: String, syntax: ObjectQuerySyntax): List<Pair<String, String>> {
    return flattenQueryObjectValue(this, emptyList()).map { (path, value) ->
        path.serialize(parameterName, syntax) to value.toStringLiteral()
    }
}

private fun flattenQueryObjectValue(value: Value, path: List<QueryObjectPathToken>): List<Pair<QueryObjectPath, Value>> {
    return when (value) {
        is JSONObjectValue -> if (value.jsonObject.isEmpty() && path.isNotEmpty()) {
            listOf(QueryObjectPath(path) to StringValue(""))
        } else {
            value.jsonObject.flatMap { (propertyName, propertyValue) ->
                flattenQueryObjectValue(propertyValue, path + QueryObjectPathToken.Property(propertyName))
            }
        }
        is JSONArrayValue -> if (value.list.isEmpty() && path.isNotEmpty()) {
            listOf(QueryObjectPath(path) to StringValue(""))
        } else {
            value.list.flatMapIndexed { index, itemValue ->
                flattenQueryObjectValue(itemValue, path + QueryObjectPathToken.Index(index))
            }
        }
        is NullValue -> emptyList()
        else -> listOf(QueryObjectPath(path) to value)
    }
}
