package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.constraintMismatchResult
import io.specmatic.core.patternMismatchResult
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.Value

data class NotEqualsPattern(val basePattern: Pattern, val excluded: ScalarValue) : Pattern by basePattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasSupportedTemplate() == true) return Result.Success()

        val baseResult = basePattern.matches(sampleData, resolver)
        if (baseResult is Result.Failure) return baseResult

        if (sampleData is ScalarValue && sampleData == excluded) {
            return constraintMismatchResult(
                expected = "value not equal to ${excluded.displayableValue()}",
                actual = sampleData,
                mismatchMessages = resolver.mismatchMessages
            )
        }

        return baseResult
    }

    override fun generate(resolver: Resolver): Value {
        return basePattern.generateNotEqualTo(excluded, resolver)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val resolvedOther = resolvedHop(otherPattern, otherResolver)
        return when (resolvedOther) {
            is ExactValuePattern -> {
                if (resolvedOther.pattern == excluded)
                    patternMismatchResult(this, otherPattern, thisResolver.mismatchMessages)
                else
                    basePattern.encompasses(resolvedOther, thisResolver, otherResolver, typeStack)
            }
            else -> basePattern.encompasses(resolvedOther, thisResolver, otherResolver, typeStack)
        }
    }

    override fun fitsWithin(
        otherPatterns: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return basePattern.fitsWithin(otherPatterns, thisResolver, otherResolver, typeStack)
    }

    override fun patternFrom(value: Value, resolver: Resolver): Pattern {
        return NotEqualsPattern(basePattern.patternFrom(value, resolver), excluded)
    }
}
