package io.specmatic.core.substitution

import io.specmatic.core.Resolver
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

internal object SubstitutionVariableExtractor {
    fun fromMap(originalMap: Map<String, String>, runningMap: Map<String, String>, resolver: Resolver = Resolver()): Map<String, Value> {
        return originalMap.entries.fold(emptyMap()) { acc, (key, originalValue) ->
            val runningValue = runningMap[key] ?: return@fold acc
            acc + InterpolatedSubstitution.extractVariables(originalValue, runningValue, resolver)
        }
    }

    fun fromPath(originalPath: String?, runningPath: String?, resolver: Resolver = Resolver()): Map<String, Value> {
        if (originalPath == null || runningPath == null) return emptyMap()
        val originalPathPieces = originalPath.split('/').filterNot(String::isBlank)
        val runningPathPieces = runningPath.split('/').filterNot(String::isBlank)
        return originalPathPieces.zip(runningPathPieces).fold(emptyMap()) { acc, (originalPiece, runningPiece) ->
            acc + InterpolatedSubstitution.extractVariables(originalPiece, runningPiece, resolver)
        }
    }

    fun fromValues(originalValue: Value, runningValue: Value, resolver: Resolver = Resolver()): Map<String, Value> {
        return when (originalValue) {
            is StringValue -> variablesFromString(originalValue, runningValue, resolver)
            is JSONObjectValue -> variablesFromObject(originalValue, runningValue, resolver)
            is JSONArrayValue -> variablesFromArray(originalValue, runningValue, resolver)
            else -> emptyMap()
        }
    }

    private fun variablesFromString(originalValue: StringValue, runningValue: Value, resolver: Resolver): Map<String, Value> {
        return InterpolatedSubstitution.extractVariables(
            resolver = resolver,
            original = originalValue.string,
            running = runningValue.toStringLiteral(),
        )
    }

    private fun variablesFromObject(originalValue: JSONObjectValue, runningValue: Value, resolver: Resolver): Map<String, Value> {
        val runningObject = runningValue as? JSONObjectValue ?: return emptyMap()
        return originalValue.jsonObject.entries.fold(emptyMap()) { acc, (key, originalChild) ->
            val runningChild = runningObject.jsonObject[key] ?: return@fold acc
            acc + fromValues(originalChild, runningChild, resolver)
        }
    }

    private fun variablesFromArray(originalValue: JSONArrayValue, runningValue: Value, resolver: Resolver): Map<String, Value> {
        val runningArray = runningValue as? JSONArrayValue ?: return emptyMap()
        return originalValue.list.zip(runningArray.list).fold(emptyMap()) { acc, (originalItem, runningItem) ->
            acc + fromValues(originalItem, runningItem, resolver)
        }
    }
}
