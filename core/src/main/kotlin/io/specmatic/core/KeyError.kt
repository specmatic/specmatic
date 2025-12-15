package io.specmatic.core

import io.specmatic.core.Result.Failure

sealed interface KeyError {
    val name: String
    val canonicalKey: String

    fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure

    fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure

    fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages = DefaultMismatchMessages): Failure
}

data class MissingKeyError(override val name: String) : KeyError {
    override val canonicalKey: String = name

    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.expectedKeyWasMissing(keyLabel, name))
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.optionalKeyMissing(keyLabel, name), isPartial = true)
    }

    override fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.unexpectedKey(keyLabel, name))
    }
}

data class UnexpectedKeyError(override val name: String) : KeyError {
    override val canonicalKey: String = name

    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.unexpectedKey(keyLabel, name))
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.unexpectedKey(keyLabel, name))
    }

    override fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(mismatchMessages.unexpectedKey(keyLabel, name))
    }
}

data class FuzzyKeyError(override val name: String, private val candidate: String, private val wasMandatory: Boolean, private val treatOptionalAsWarning: Boolean = false) : KeyError {
    override val canonicalKey: String = candidate

    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        val msg = mismatchMessages.unexpectedKey(keyLabel, name) + ". Did you mean \"$candidate\"?"
        return Failure(msg)
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        val msg = mismatchMessages.unexpectedKey(keyLabel, name) + ". Did you mean \"$candidate\"?"
        return Failure(msg, isPartial = treatOptionalAsWarning)
    }

    override fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return if (wasMandatory) {
            missingKeyToResult(keyLabel, mismatchMessages)
        } else {
            missingOptionalKeyToResult(keyLabel, mismatchMessages)
        }
    }
}
