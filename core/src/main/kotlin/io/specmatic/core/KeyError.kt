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

data class UnexpectedKeyError(override val name: String) : KeyError {
    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure =
        Failure(mismatchMessages.unexpectedKey(keyLabel, name))

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure =
        Failure(mismatchMessages.unexpectedKey(keyLabel, name))
}

data class FuzzyKeyError(override val name: String, val candidate: String, val isWarning: Boolean) : KeyError {
    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return missingOptionalKeyToResult(keyLabel, mismatchMessages)
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        val msg = mismatchMessages.unexpectedKey(keyLabel, name) + ", Did you mean \"$candidate\" ?"
        return Failure(msg, isPartial = isWarning)
    }
}
