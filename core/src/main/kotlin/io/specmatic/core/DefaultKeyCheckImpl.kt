package io.specmatic.core

import io.specmatic.core.pattern.IgnoreUnexpectedKeys

data class DefaultKeyCheckImpl(
    private val patternKeyCheck: KeyErrorCheck = CheckOnlyPatternKeys,
    private val unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys,
    private val disableOverrideUnexpectedKeyCheck: Boolean = false
) : KeyCheck {
    override val isPartial: Boolean = patternKeyCheck == noPatternKeyCheck
    override val isExtensible: Boolean = unexpectedKeyCheck == IgnoreUnexpectedKeys

    override fun disableOverrideUnexpectedKeyCheck(): KeyCheck {
        return this.copy(disableOverrideUnexpectedKeyCheck = true)
    }

    override fun withUnexpectedKeyCheck(unexpectedKeyCheck: UnexpectedKeyCheck): KeyCheck {
        if (disableOverrideUnexpectedKeyCheck) return this
        return this.copy(unexpectedKeyCheck = unexpectedKeyCheck)
    }

    override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError? {
        return validateAll(pattern, actual).firstOrNull()
    }

    override fun validateAll(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
        return patternKeyCheck.validateList(pattern, actual).plus(unexpectedKeyCheck.validateList(pattern, actual))
    }

    override fun validateAllCaseInsensitive(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
        return patternKeyCheck.validateListCaseInsensitive(pattern, actual).plus(unexpectedKeyCheck.validateListCaseInsensitive(pattern, actual))
    }

    override fun toPartialKeyCheck(): KeyCheck {
        return this.copy(patternKeyCheck = noPatternKeyCheck)
    }
}
