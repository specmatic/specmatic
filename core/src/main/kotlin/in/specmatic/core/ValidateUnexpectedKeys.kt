package `in`.specmatic.core

import `in`.specmatic.core.pattern.withoutOptionality

object ValidateUnexpectedKeys: UnexpectedKeyCheck {
    override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyError? {
        return validateList(pattern, actual).firstOrNull()
    }

    override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyError> {
        val patternKeys = pattern.minus("...").keys.map { withoutOptionality(it) }
        val actualKeys = actual.keys.map { withoutOptionality(it) }

        return actualKeys.minus(patternKeys.toSet()).map {
            UnexpectedKeyError(it)
        }
    }
}
