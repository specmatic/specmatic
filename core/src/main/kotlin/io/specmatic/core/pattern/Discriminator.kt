package io.specmatic.core.pattern

import io.specmatic.core.FailureReason
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.pattern.AnyPattern.AnyPatternMatch
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.core.valueMismatchResult
import io.specmatic.test.ExampleProcessor

class Discriminator(
    val property: String,
    val values: Set<String>,
    val mapping: Map<String, String>
) {
    companion object {
        fun create(
            discriminatorProperty: String? = null,
            discriminatorValues: Set<String> = emptySet(),
            mapping: Map<String, String>
        ): Discriminator? {
            if(discriminatorProperty == null || discriminatorValues.isEmpty())
                return null

            return Discriminator(discriminatorProperty, discriminatorValues, mapping)
        }
    }

    fun isNotEmpty(): Boolean = values.isNotEmpty()

    fun hasMultipleValues(): Boolean = values.size > 1

    fun matches(sampleData: Value?, patterns: List<Pattern>, key: String?, resolver: Resolver): Result {
        val updatedPatterns: ReturnValue<List<Pattern>> = updatePatternsWithDiscriminator(patterns, resolver).listFold()

        return when(updatedPatterns) {
            is HasValue<List<Pattern>> -> _matches(sampleData, updatedPatterns.value, key, resolver)
            is HasFailure -> updatedPatterns.failure
            is HasException -> updatedPatterns.toHasFailure().failure
        }
    }

    fun updatePatternsWithDiscriminator(
        patterns: List<Pattern>,
        resolver: Resolver
    ): List<ReturnValue<Pattern>> {
        val schemaNameToDiscriminatorValueMapping =
            mapping.mapValues { it.value.split("/").last() }.map { it.value to it.key }.toMap()

        val updatedPatterns: List<ReturnValue<Pattern>> = patterns.map { pattern ->
            val resolved = resolvedHop(pattern, resolver)

            if (resolved !is JSONObjectPattern)
                return@map HasValue(resolved)

            val typeAlias = resolved.typeAlias ?: return@map HasValue(resolved)

            val schemaName = withoutPatternDelimiters(typeAlias)

            val discriminatorValue = schemaNameToDiscriminatorValueMapping[schemaName] ?: return@map HasValue(resolved)

            resolved.updateWithDiscriminatorValue(property, discriminatorValue, resolver)
        }

        return updatedPatterns
    }

    private fun valueHasDiscriminator(sampleData: JSONObjectValue): Boolean {
        return sampleData.jsonObject[property] != null
    }

    private fun _matches_partial(sampleData: JSONObjectValue, patterns: List<Pattern>, key: String?, resolver: Resolver): Result {
        val matchResults: List<AnyPatternMatch> = patterns.map {
            AnyPatternMatch(it, resolver.matchesPattern(key, it, sampleData))
        }

        val matchResult = matchResults.find { it.result is Result.Success }
        if(matchResult != null) return matchResult.result

        val failuresWithUpdatedBreadcrumbs = matchResults.mapIndexedNotNull { index, match ->
            val failure = match.result as? Result.Failure ?: return@mapIndexedNotNull null
            val breadcrumb = when (val typeAlias = match.pattern.typeAlias) {
                null -> return@mapIndexedNotNull failure
                "()" -> "(~~~object ${index + 1})"
                else -> "(~~~${withoutPatternDelimiters(typeAlias)} object)"
            }
            failure.breadCrumb(breadcrumb)
        }

        return Failure.fromFailures(failures = failuresWithUpdatedBreadcrumbs).withRuleViolation(
            ruleViolation = StandardRuleViolation.ONE_OF_VALUE_MISMATCH
        )
    }

    private fun _matches(sampleData: Value?, pattern: List<Pattern>, key: String?, resolver: Resolver): Result {
        if (sampleData !is JSONObjectValue)
            return jsonObjectMismatchError(resolver, sampleData)

        if (!valueHasDiscriminator(sampleData) && resolver.hasPartialKeyCheck()) {
            return _matches_partial(sampleData, pattern, key, resolver)
        }

        val discriminatorCsvClause = if(values.size ==  1)
            values.first()
        else
            "one of ${values.joinToString(", ")}"

        val actualDiscriminatorValue = sampleData.jsonObject[property] ?: return discriminatorKeyMissingFailure(
            property,
            discriminatorCsvClause
        )

        if (isPatternToken(actualDiscriminatorValue) || ExampleProcessor.isSubstitutionToken(actualDiscriminatorValue)) return Result.Success()
        if (actualDiscriminatorValue.toStringLiteral() !in values) {
            val message = "Expected the value of discriminator property to be $discriminatorCsvClause but it was ${
                actualDiscriminatorValue.toStringLiteral()
                    .takeUnless { it.isEmpty() } ?: "\"\""
            }"

            return Result.Failure(
                message,
                breadCrumb = property,
                failureReason = FailureReason.DiscriminatorMismatch,
                ruleViolation = StandardRuleViolation.DISCRIMINATOR_MISMATCH
            )
        }

        val emptyPatternMatchResults = Pair(emptyList<AnyPattern.AnyPatternMatch>(), false)

        val (matchResults: List<AnyPattern.AnyPatternMatch>, discriminatorMatchOccurred: Boolean) = pattern.fold(emptyPatternMatchResults) { acc, pattern ->
            val (resultsSoFar, discriminatorMatchHasOccurred) = acc

            if (discriminatorMatchHasOccurred) {
                return@fold resultsSoFar.plus(discriminatorMatchFailure(pattern)) to true
            }

            val discriminatorProbe = JSONObjectValue(mapOf(property to actualDiscriminatorValue))

            val discriminatorMatched = pattern.matches(discriminatorProbe, resolver).let { probeResult ->
                probeResult is Result.Success
                        || (probeResult is Result.Failure && probeResult.hasReason(FailureReason.FailedButDiscriminatorMatched))
            }

            if(discriminatorMatched)
                resultsSoFar.plus((AnyPattern.AnyPatternMatch(
                    pattern,
                    resolver.matchesPattern(key, pattern, sampleData)
                ))) to true
            else
                resultsSoFar.plus(discriminatorMatchFailure(pattern)) to false
        }

        if(!discriminatorMatchOccurred) {
            return Result.Failure(
                "Discriminator property $property is missing from the spec",
                breadCrumb = property,
                failureReason = FailureReason.DiscriminatorMismatch,
                ruleViolation = StandardRuleViolation.INVALID_DISCRIMINATOR_SETUP
            )
        }

        val matchResult = matchResults.find { it.result is Result.Success }

        if(matchResult != null)
            return matchResult.result

        val failures = matchResults.map { it.result }.filterIsInstance<Result.Failure>()

        val deepMatchResults = failures.filter { it.hasReason(FailureReason.FailedButDiscriminatorMatched) }

        return if (deepMatchResults.isNotEmpty())
            Result.Failure.fromFailures(deepMatchResults).removeReasonsFromCauses().copy(failureReason = FailureReason.FailedButDiscriminatorMatched)
        else
            Result.Failure.fromFailures(failures).removeReasonsFromCauses()
    }

    private fun discriminatorMatchFailure(pattern: Pattern) = AnyPattern.AnyPatternMatch(
        pattern,
        Result.Failure(
            "Discriminator match failure",
            failureReason = FailureReason.DiscriminatorMismatch,
            ruleViolation = StandardRuleViolation.DISCRIMINATOR_MISMATCH
        )
    )

    private fun jsonObjectMismatchError(
        resolver: Resolver,
        sampleData: Value?
    ) = valueMismatchResult("json object", sampleData, resolver.mismatchMessages)

    private fun discriminatorKeyMissingFailure(discriminatorProperty: String, discriminatorCsv: String) =
        Result.Failure(
            "Discriminator property $discriminatorProperty is missing from the object (it's value should be $discriminatorCsv)",
            breadCrumb = discriminatorProperty,
            failureReason = FailureReason.DiscriminatorMismatch,
            ruleViolation = StandardRuleViolation.MISSING_DISCRIMINATOR
        )

    fun removeKeyFromRow(row: Row): Row {
        return row.removeKey(property)
    }
}