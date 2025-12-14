package io.specmatic.core

import io.specmatic.core.pattern.isOptional

internal object CheckOnlyPatternKeys: KeyErrorCheck {
    override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): MissingKeyError? {
        return validateList(pattern, actual).firstOrNull()
    }

    override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
        return pattern.minus("...").keys.filter { key ->
            isMissingKey(actual, key)
        }.map { it.toMissingKeyError() }
    }

    override fun validateListCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<MissingKeyError> {
        return pattern.minus("...").keys.filter { key ->
            isMissingKeyCaseInsensitive(actual, key)
        }.map { it.toMissingKeyError() }
    }
}

internal fun String.toMissingKeyError(): MissingKeyError {
    return MissingKeyError(this)
}

internal fun isMissingKey(jsonObject: Map<String, Any?>, key: String) =
    when {
        isOptional(key) -> false
        else -> key !in jsonObject && "$key?" !in jsonObject && "$key:" !in jsonObject
    }

internal fun isMissingKeyCaseInsensitive(jsonObject: Map<String, Any?>, key: String) =
    when {
        isOptional(key) -> false
        else -> {
            val objectWithLowerCaseKeys = jsonObject.mapKeys { it.key.lowercase() }
            val lowerCaseKey = key.lowercase()

            lowerCaseKey !in objectWithLowerCaseKeys && "$lowerCaseKey?" !in objectWithLowerCaseKeys && "$lowerCaseKey:" !in objectWithLowerCaseKeys
        }
    }
