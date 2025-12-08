package io.specmatic.core

import io.specmatic.core.fuzzy.FuzzyMatchResult
import io.specmatic.core.fuzzy.FuzzyMatcher
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.withoutOptionality
import io.specmatic.core.value.StringValue

class FuzzyUnexpectedKeyCheck(
    private val delegate: UnexpectedKeyCheck,
    private val treatFuzzyMatchesAsWarnings: Boolean = (delegate is IgnoreUnexpectedKeys)
) : UnexpectedKeyCheck {
    override fun validate(pattern: Map<String, Any>, actual: Map<String, Any>): UnexpectedKeyErrorType? {
        return validateList(pattern, actual).firstOrNull()
    }

    override fun validateList(pattern: Map<String, Any>, actual: Map<String, Any>): List<UnexpectedKeyErrorType> {
        return performFuzzyCheck(
            pattern = pattern,
            actual = actual,
            unexpectedKeyStrategy = { p, a -> ValidateUnexpectedKeys.validateList(p, a) },
            delegateStrategy = { p, a -> delegate.validateList(p, a) }
        )
    }

    override fun validateListCaseInsensitive(pattern: Map<String, Pattern>, actual: Map<String, StringValue>): List<UnexpectedKeyErrorType> {
        return performFuzzyCheck(
            pattern = pattern,
            actual = actual,
            unexpectedKeyStrategy = { p, a -> ValidateUnexpectedKeys.validateListCaseInsensitive(p, a) },
            delegateStrategy = { p, a -> delegate.validateListCaseInsensitive(p, a) }
        )
    }

    private fun <T, U> performFuzzyCheck(
        pattern: Map<String, T>, actual: Map<String, U>,
        unexpectedKeyStrategy: (Map<String, T>, Map<String, U>) -> List<UnexpectedKeyErrorType>,
        delegateStrategy: (Map<String, T>, Map<String, U>) -> List<UnexpectedKeyErrorType>
    ): List<UnexpectedKeyErrorType> {
        val unexpectedErrors  = unexpectedKeyStrategy(pattern, actual)
        if (unexpectedErrors .isEmpty()) return delegateStrategy(pattern, actual)

        val unexpectedKeys = unexpectedErrors.map { it.name }
        val fuzzyErrors = findFuzzyMatches(unexpectedKeys = unexpectedKeys, validKeys = pattern.keys, actualKeys = actual.keys)
        val delegateErrors = delegateStrategy(pattern, actual)
        return mergeErrors(fuzzyErrors, delegateErrors)
    }

    private fun findFuzzyMatches(unexpectedKeys: List<String>, validKeys: Set<String>, actualKeys: Set<String>): List<FuzzyKeyError> {
        val keysWithoutOptionality = validKeys.map(::withoutOptionality).toSet()
        val fuzzyMatcher = FuzzyMatcher.Companion.FuzzyBuilder().fromKeys(keysWithoutOptionality).build()
        return unexpectedKeys.mapNotNull { key ->
            val match = fuzzyMatcher.match(key)
            if (match is FuzzyMatchResult.FuzzyMatch && match.key !in actualKeys) {
                FuzzyKeyError(key, match.key, treatFuzzyMatchesAsWarnings)
            } else {
                null
            }
        }
    }

    private fun mergeErrors(fuzzyErrors: List<FuzzyKeyError>, delegateErrors: List<UnexpectedKeyErrorType>): List<UnexpectedKeyErrorType> {
        if (fuzzyErrors.isEmpty()) return delegateErrors
        val fuzzyKeyNames = fuzzyErrors.map { it.name }.toSet()
        return fuzzyErrors + delegateErrors.filter { it.name !in fuzzyKeyNames }
    }
}
