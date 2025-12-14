package io.specmatic.core

import io.specmatic.core.fuzzy.FuzzyMatchResult
import io.specmatic.core.fuzzy.FuzzyMatcher
import io.specmatic.core.fuzzy.FuzzyMatcher.Companion.buildFuzzyMatcher
import io.specmatic.core.pattern.withoutOptionality

private typealias UnexpectedKeysFinder = (AnyValueMap, AnyValueMap) -> List<UnexpectedKeyError>
private typealias AnyValueMap = Map<String, Any>

class FuzzyKeyCheck(private val delegate: KeyCheck = DefaultKeyCheckImpl()): KeyCheck by delegate {
    constructor(keyErrorCheck: KeyErrorCheck = CheckOnlyPatternKeys, unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys) : this(
        DefaultKeyCheckImpl(keyErrorCheck, unexpectedKeyCheck)
    )

    override fun validateAllCaseInsensitive(pattern: AnyValueMap, actual: AnyValueMap): List<KeyError> {
        val delegateErrors = delegate.validateAllCaseInsensitive(pattern, actual)
        return refineKeyErrors(pattern, actual, delegateErrors) { pattern, actual ->
            ValidateUnexpectedKeys.validateListCaseInsensitive(pattern, actual)
        }
    }

    override fun validateAll(pattern: AnyValueMap, actual: AnyValueMap): List<KeyError> {
        val delegateErrors = delegate.validateAll(pattern, actual)
        return refineKeyErrors(pattern, actual, delegateErrors) { pattern, actual ->
            ValidateUnexpectedKeys.validateList(pattern, actual)
        }
    }

    private fun refineKeyErrors(pattern: AnyValueMap, actual: AnyValueMap, errors: List<KeyError>, unexpectedKeysFinder: UnexpectedKeysFinder): List<KeyError> {
        val fuzzyMatcher: FuzzyMatcher by lazy(LazyThreadSafetyMode.NONE) {
            buildFuzzyMatcher {
                fromKeys(pattern.keys.map(::withoutOptionality).toSet())
            }
        }

        val unexpectedKeyErrors = errors.filterIsInstance<UnexpectedKeyError>()
        val refinedUnexpectedKeyErrors = refineUnexpectedKeyErrors(pattern, actual, unexpectedKeyErrors, fuzzyMatcher)
        val finalUnexpectedKeyErrors = refinedUnexpectedKeyErrors.ifEmpty {
            findOptionalKeyErrors(pattern, actual, fuzzyMatcher, unexpectedKeysFinder)
        }

        val missingKeyErrors = errors.filterIsInstance<MissingKeyError>()
        val foundValidKeys = finalUnexpectedKeyErrors.filterIsInstance<FuzzyKeyError>().map { it.canonicalKey }.toSet()
        val refinedMissingKeyErrors = refineMissingKeyErrors(pattern, actual, missingKeyErrors, foundValidKeys, fuzzyMatcher)

        return refinedMissingKeyErrors.plus(finalUnexpectedKeyErrors)
    }

    private fun refineMissingKeyErrors(pattern: AnyValueMap, actual: AnyValueMap, errors: List<MissingKeyError>, foundValidKeys: Set<String>, fuzzyMatcher: FuzzyMatcher): List<KeyError> {
        return errors.mapNotNull { missingKeyError ->
            if (foundValidKeys.contains(missingKeyError.name)) return@mapNotNull null
            val result = fuzzyMatcher.findNearestIn(actual.keys, missingKeyError.name)
            if (result !is FuzzyMatchResult.FuzzyMatch) return@mapNotNull missingKeyError
            FuzzyKeyError(result.key, missingKeyError.name, pattern.contains(missingKeyError.name))
        }
    }

    private fun refineUnexpectedKeyErrors(pattern: AnyValueMap, actual: AnyValueMap, errors: List<UnexpectedKeyError>, fuzzyMatcher: FuzzyMatcher): List<KeyError> {
        val claimedKeys = actual.keys.toMutableSet()
        return errors.map { unexpectedKeyError ->
            val result = fuzzyMatcher.match(unexpectedKeyError.name)
            if (result !is FuzzyMatchResult.FuzzyMatch || !claimedKeys.add(result.key)) return@map unexpectedKeyError
            FuzzyKeyError(unexpectedKeyError.name, result.key, pattern.contains(result.key))
        }
    }

    private fun findOptionalKeyErrors(pattern: AnyValueMap, actual: AnyValueMap, fuzzyMatcher: FuzzyMatcher, findUnexpected: UnexpectedKeysFinder): List<KeyError> {
        if (delegate.isPartial) return emptyList()
        val strictUnexpectedKeyErrors = findUnexpected(pattern, actual)
        val claimedKeys = actual.keys.toMutableSet()
        return strictUnexpectedKeyErrors.mapNotNull { unexpectedKeyError ->
            val result = fuzzyMatcher.match(unexpectedKeyError.name)
            if (result !is FuzzyMatchResult.FuzzyMatch || !claimedKeys.add(result.key)) return@mapNotNull null
            FuzzyKeyError(unexpectedKeyError.name, result.key, pattern.contains(result.key), treatOptionalAsWarning = true)
        }
    }
}
