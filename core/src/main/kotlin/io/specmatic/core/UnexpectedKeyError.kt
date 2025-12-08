package io.specmatic.core

import io.specmatic.core.Result.Failure

interface UnexpectedKeyErrorType : KeyError

data class UnexpectedKeyError(override val name: String) : UnexpectedKeyErrorType {
    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure =
        Failure(mismatchMessages.unexpectedKey(keyLabel, name))

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure =
        Failure(mismatchMessages.unexpectedKey(keyLabel, name))
}

data class FuzzyKeyError(override val name: String, val candidate: String, val isWarning: Boolean) : UnexpectedKeyErrorType {
    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return missingOptionalKeyToResult(keyLabel, mismatchMessages)
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        val msg = mismatchMessages.unexpectedKey(keyLabel, name) + ", Did you mean \"$candidate\" ?"
        return Failure(msg, isPartial = isWarning)
    }
}
