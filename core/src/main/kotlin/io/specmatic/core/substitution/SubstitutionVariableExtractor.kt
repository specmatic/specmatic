package io.specmatic.core.substitution

import io.specmatic.core.HttpRequest
import io.specmatic.core.pattern.isPatternToken
import io.specmatic.core.pattern.withoutPatternDelimiters
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal object SubstitutionVariableExtractor {
    fun fromRequest(runningRequest: HttpRequest, originalRequest: HttpRequest): Map<String, String> {
        val variableValuesFromHeaders = variablesFromMap(
            map = runningRequest.headers.filter { it.key in originalRequest.headers },
            originalMap = originalRequest.headers
        )

        val variableValuesFromQueryParams = variablesFromMap(
            map = runningRequest.queryParams.asMap(),
            originalMap = originalRequest.queryParams.asMap()
        )

        val runningPathPieces = runningRequest.path!!.split('/').filterNot { it.isBlank() }
        val originalPathPieces = originalRequest.path!!.split('/').filterNot { it.isBlank() }
        val variableValuesFromPath = runningPathPieces
            .zip(originalPathPieces)
            .mapNotNull { (runningPiece, originalPiece) -> variableFromString(runningPiece, originalPiece) }
            .toMap()

        val variableValuesFromRequestBody: Map<String, String> = getVariableValuesFromValue(runningRequest.body, originalRequest.body)
        return variableValuesFromHeaders + variableValuesFromRequestBody + variableValuesFromQueryParams + variableValuesFromPath
    }

    private fun variableFromString(value: String, originalValue: String): Pair<String, String>? {
        if (!isPatternToken(originalValue)) return null
        val pieces = withoutPatternDelimiters(originalValue).split(":")
        val name = pieces.getOrNull(0) ?: return null
        return Pair(name, value)
    }

    private fun variablesFromMap(map: Map<String, String>, originalMap: Map<String, String>) = map.entries.map { (key, value) ->
        val originalValue = originalMap[key] ?: return@map null
        variableFromString(value, originalValue)
    }.filterNotNull().toMap()

    private fun getVariableValuesFromValue(value: JSONObjectValue, originalValue: JSONObjectValue): Map<String, String> {
        return originalValue.jsonObject.entries.fold(emptyMap()) { acc, entry ->
            val runningValue = value.jsonObject.getValue(entry.key)
            acc + getVariableValuesFromValue(runningValue, entry.value)
        }
    }

    private fun getVariableValuesFromValue(value: JSONArrayValue, originalValue: JSONArrayValue): Map<String, String> {
        return originalValue.list.foldRightIndexed(emptyMap()) { index: Int, item: Value, acc: Map<String, String> ->
            val runningItem = value.list[index]
            acc + getVariableValuesFromValue(runningItem, item)
        }
    }

    private fun getVariableValuesFromValue(value: Value, originalValue: Value): Map<String, String> {
        return when (originalValue) {
            is StringValue -> {
                val variable = variableFromString(value = value.toStringLiteral(), originalValue = originalValue.string)
                variable?.let { mapOf(it.first to it.second) }.orEmpty()
            }
            is JSONObjectValue -> getVariableValuesFromValue(value as JSONObjectValue, originalValue)
            is JSONArrayValue -> getVariableValuesFromValue(value as JSONArrayValue, originalValue)
            else -> emptyMap()
        }
    }
}
