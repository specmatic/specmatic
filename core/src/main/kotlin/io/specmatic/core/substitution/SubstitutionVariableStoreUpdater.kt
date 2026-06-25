package io.specmatic.core.substitution

import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal object SubstitutionVariableStoreUpdater {
    fun fromValues(originalValue: Value, runningValue: Value): Map<String, String> {
        return variablesFromValue(originalValue, runningValue)
    }

    fun fromMap(originalMap: Map<String, String>, runningMap: Map<String, String>): Map<String, String> {
        return originalMap.entries.fold(emptyMap()) { acc, (key, originalValue) ->
            val runningValue = runningMap[key] ?: return@fold acc
            acc + InterpolatedSubstitution.extractVariables(originalValue, runningValue)
        }
    }

    fun fromPath(originalPath: String?, runningPath: String?): Map<String, String> {
        if (originalPath == null || runningPath == null) return emptyMap()
        val originalPathPieces = originalPath.split('/').filterNot(String::isBlank)
        val runningPathPieces = runningPath.split('/').filterNot(String::isBlank)
        return originalPathPieces.zip(runningPathPieces).fold(emptyMap()) { acc, (originalPiece, runningPiece) ->
            acc + InterpolatedSubstitution.extractVariables(originalPiece, runningPiece)
        }
    }

    private fun variablesFromValue(originalValue: Value, runningValue: Value): Map<String, String> {
        return when (originalValue) {
            is StringValue -> InterpolatedSubstitution.extractVariables(originalValue.string, runningValue.toStringLiteral())
            is JSONObjectValue -> variablesFromObject(originalValue, runningValue)
            is JSONArrayValue -> variablesFromArray(originalValue, runningValue)
            else -> emptyMap()
        }
    }

    private fun variablesFromObject(originalValue: JSONObjectValue, runningValue: Value): Map<String, String> {
        val runningObject = runningValue as? JSONObjectValue ?: return emptyMap()
        return originalValue.jsonObject.entries.fold(emptyMap()) { acc, (key, originalChild) ->
            val runningChild = runningObject.jsonObject[key] ?: return@fold acc
            acc + variablesFromValue(originalChild, runningChild)
        }
    }

    private fun variablesFromArray(originalValue: JSONArrayValue, runningValue: Value): Map<String, String> {
        val runningArray = runningValue as? JSONArrayValue ?: return emptyMap()
        return originalValue.list.zip(runningArray.list).fold(emptyMap()) { acc, (originalItem, runningItem) ->
            acc + variablesFromValue(originalItem, runningItem)
        }
    }
}
