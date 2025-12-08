package io.specmatic.core

import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.StringValue

data class KeyCheck(val patternKeyCheck: KeyErrorCheck = CheckOnlyPatternKeys,
               var unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys,
               val overrideUnexpectedKeyCheck: OverrideUnexpectedKeyCheck? = ::overrideUnexpectedKeyCheck
) {
    fun disableOverrideUnexpectedKeycheck(): KeyCheck {
        return KeyCheck(patternKeyCheck, unexpectedKeyCheck, null)
    }

    fun withUnexpectedKeyCheck(unexpectedKeyCheck: UnexpectedKeyCheck): KeyCheck {
        return overrideUnexpectedKeyCheck?.invoke(this, unexpectedKeyCheck)
            ?: this
    }

    fun validate(
        pattern: Map<String, Any>,
        actual: Map<String, Any>
    ): KeyError? {
        return validateAll(pattern, actual).firstOrNull()
    }

    fun validateAll(
        pattern: Map<String, Any>,
        actual: Map<String, Any>
    ): List<KeyError> {
        return patternKeyCheck.validateList(pattern, actual).plus(unexpectedKeyCheck.validateList(pattern, actual))
    }

    fun validateAllCaseInsensitive(pattern: Map<String, Pattern>, actual: Map<String, StringValue>): List<KeyError> {
        return patternKeyCheck.validateListCaseInsensitive(pattern, actual).plus(unexpectedKeyCheck.validateListCaseInsensitive(pattern, actual))
    }

    fun isPartial(): Boolean {
        return patternKeyCheck == noPatternKeyCheck
    }

    fun toPartialKeyCheck(): KeyCheck {
        return this.copy(patternKeyCheck = noPatternKeyCheck)
    }

    fun isIgnoreUnexpectedKeyCheck(): Boolean = when (unexpectedKeyCheck) {
        is FuzzyUnexpectedKeyCheck -> (unexpectedKeyCheck as FuzzyUnexpectedKeyCheck).delegate is IgnoreUnexpectedKeys
        else -> unexpectedKeyCheck is IgnoreUnexpectedKeys
    }

    fun isValidateUnexpectedKeyCheck(): Boolean = !isIgnoreUnexpectedKeyCheck()
}

private fun overrideUnexpectedKeyCheck(keyCheck: KeyCheck, unexpectedKeyCheck: UnexpectedKeyCheck): KeyCheck {
    return KeyCheck(keyCheck.patternKeyCheck, unexpectedKeyCheck)
}

typealias OverrideUnexpectedKeyCheck = (KeyCheck, UnexpectedKeyCheck) -> KeyCheck

val DefaultKeyCheck = KeyCheck()
