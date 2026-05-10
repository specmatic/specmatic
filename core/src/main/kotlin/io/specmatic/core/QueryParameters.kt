package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.parsedJSONArray
import io.specmatic.core.pattern.parsedScalarValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.Value

data class QueryParameters(val paramPairs: List<Pair<String, String>> = emptyList())  {

    constructor(map: Map<String, String>) : this(mapToListOfPairs(map))

    val keys = paramPairs.map { it.first }.toSet()

    fun remove(key: String): QueryParameters {
        return QueryParameters(paramPairs.filterNot { it.first == key })
    }

    fun plus(map: Map<String, String>): QueryParameters {
        val newListOfPairs = mapToListOfPairs(map)
        return QueryParameters( paramPairs + newListOfPairs)
    }

    fun plus(pair: Pair<String, String>): QueryParameters {
        val newListOfPairs = pairToListOfPairs(pair)
        return QueryParameters( paramPairs + newListOfPairs)
    }

    fun minus(name: String): QueryParameters {
        return QueryParameters( paramPairs.filterNot { it.first == name })
    }

    fun asMap(): Map<String, String> {
        return paramPairs.groupBy { it.first }.map { (parameterName, parameterValues) ->
            if (parameterValues.size > 1) {
                parameterName to parameterValues.map { it.second }.toString()
            } else {
                parameterName to parameterValues.single().second
            }
        }.toMap()
    }

    fun asJsonMap(): Map<String, Any> {
        return paramPairs.groupBy { it.first }.map { (parameterName, parameterValues) ->
            if (parameterValues.size > 1) {
                parameterName to parameterValues.map { it.second }
            } else {
                parameterName to parameterValues.single().second
            }
        }.toMap()
    }

    fun asValueMap(): Map<String, Value> {
        return paramPairs.groupBy { it.first }.map { (parameterName, parameterValues) ->
            if (parameterValues.size > 1) {
                parameterName to JSONArrayValue(parameterValues.map { parsedScalarValue(it.second) })
            } else {
                parameterName to parsedScalarValue(parameterValues.first().second)
            }
        }.toMap()
    }

    fun getValues(key: String): List<String> {
        return paramPairs.filter { it.first == key }.map { it.second }
    }

    fun containsKey(key: String): Boolean {
        return paramPairs.any { it.first == key }
    }

    fun isNotEmpty(): Boolean {
        return paramPairs.isNotEmpty()
    }

    fun containsEntry(key:String, value: String): Boolean {
        return paramPairs.any { it.first == key && it.second == value }
    }

    fun getOrElse(key: String, defaultValue: () -> String): String {
        return when {
            containsKey(key) -> {
                getValues(key).first()
            }
            else -> defaultValue()
        }
    }

    fun getOrDefault(key: String, defaultValue: String): String {
        return when {
            containsKey(key) -> {
                getValues(key).first()
            }
            else -> defaultValue
        }
    }

    fun toLine(): String {
        return paramPairs.joinToString(" "){ (key, value) ->
            "$key=$value"
        }
    }

    fun withoutMatching(patternKeys: Set<String>, additionalProperties: Pattern, resolver: Resolver): QueryParameters {
        return QueryParameters(paramPairs.filterNot { (key, value) ->
            matchesAdditionalProperty(key, value, patternKeys, additionalProperties, resolver)
        })
    }

    fun matching(patternKeys: Set<String>, additionalProperties: Pattern, resolver: Resolver): QueryParameters {
        return QueryParameters(paramPairs.filter { (key, value) ->
            matchesAdditionalProperty(key, value, patternKeys, additionalProperties, resolver)
        })
    }

    private fun matchesAdditionalProperty(key: String, value: String, patternKeys: Set<String>, additionalProperties: Pattern, resolver: Resolver): Boolean {
        if(key in patternKeys)
            return false

        return try {
            val parsedValue: Value = additionalProperties.parse(value, resolver)
            additionalProperties.matches(parsedValue, resolver) is Result.Success
        } catch(e: Throwable) {
            false
        }
    }
}

fun paramPairsExpanded(inputList: List<Pair<String, String>>): List<Pair<String, String>> {
    return inputList.flatMap { (key, value) ->
        toListOfPairs(value, key)
    }
}

fun mapToListOfPairs(inputMap: Map<String, String>): List<Pair<String, String>> {
    return inputMap.flatMap { (key, value) ->
        toListOfPairs(value, key)
    }
}

fun listOfPairsToMap(inputList: List<Pair<String, String>>): Map<String, String> {
    return inputList.toMap()
}

fun pairToListOfPairs(pair: Pair<String, String>): List<Pair<String, String>> {
    val key = pair.first
    val value = pair.second
    return toListOfPairs(value, key)
}

private fun toListOfPairs(
    value: String,
    key: String
): List<Pair<String, String>> {
    return if (isJsonArrayString(value)) {
        convertJsonArrayStringToListOfPairs(value, key)
    } else {
        listOf(key to value)
    }
}

private fun convertJsonArrayStringToListOfPairs(
    value: String,
    key: String
) = parsedJSONArray(value)
    .list
    .map { valueItem ->
        key to valueItem.toString().trim()
    }

private fun isJsonArrayString(value: String) = value.startsWith("[") && value.endsWith("]")
