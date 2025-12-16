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
        return Failure(
            message = mismatchMessages.expectedKeyWasMissing(keyLabel, name),
            ruleViolationSegment = StandardRuleViolationSegment.MissingMandatoryKey
        )
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(
            message = mismatchMessages.optionalKeyMissing(keyLabel, name),
            ruleViolationSegment = StandardRuleViolationSegment.MissingOptionalKey,
            isPartial = true
        )
    }

    override fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(
            message = mismatchMessages.unexpectedKey(keyLabel, name),
            ruleViolationSegment = StandardRuleViolationSegment.UnknownKey
        )
    }
}

data class UnexpectedKeyError(override val name: String) : KeyError {
    override val canonicalKey: String = name

    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return missingOptionalKeyToResult(keyLabel, mismatchMessages)
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return unknownKeyToResult(keyLabel, mismatchMessages)
    }

    override fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(
            message = mismatchMessages.unexpectedKey(keyLabel, name),
            ruleViolationSegment = StandardRuleViolationSegment.UnknownKey
        )
    }
}

data class FuzzyKeyError(override val name: String, private val candidate: String, private val wasMandatory: Boolean, private val treatOptionalAsWarning: Boolean = false) : KeyError {
    override val canonicalKey: String = candidate

    override fun missingKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(
            message = mismatchMessages.unexpectedKey(keyLabel, name) + ". Did you mean \"$candidate\"?",
            ruleViolationSegment = StandardRuleViolationSegment.FuzzyMatchMissingMandatoryKey
        )
    }

    override fun missingOptionalKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return Failure(
            message = mismatchMessages.unexpectedKey(keyLabel, name) + ". Did you mean \"$candidate\"?",
            ruleViolationSegment = StandardRuleViolationSegment.FuzzyMatchMissingOptionalKey,
            isPartial = treatOptionalAsWarning,
        )
    }

    override fun unknownKeyToResult(keyLabel: String, mismatchMessages: MismatchMessages): Failure {
        return if (wasMandatory) {
            missingKeyToResult(keyLabel, mismatchMessages)
        } else {
            missingOptionalKeyToResult(keyLabel, mismatchMessages)
        }
    }
}
