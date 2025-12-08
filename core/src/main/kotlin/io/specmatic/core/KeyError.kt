package io.specmatic.core

import io.specmatic.core.Result.Failure

sealed interface KeyError {
    val name: String

    fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure

    fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure {
        return missingKeyToResult(keyLabel, mismatchMessages).copy(isPartial = true)
    }
}

data class MissingKeyError(override val name: String) : KeyError {
    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure =
        Failure(mismatchMessages.expectedKeyWasMissing(keyLabel, name))

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.optionalKeyMissing(keyLabel, name), isPartial = true)
    }
}
