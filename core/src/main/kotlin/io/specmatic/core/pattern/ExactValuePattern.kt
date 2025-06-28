package io.specmatic.core.pattern

import io.specmatic.core.FailureReason
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.mismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.Value

data class ExactValuePattern(override val pattern: Value, override val typeAlias: String? = null, val discriminator: Boolean = false) : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (pattern == sampleData) {
            true -> Result.Success()
            else -> {
                if (discriminator) {
                    val errorMessage = "Expected the value of discriminator property to be ${pattern.displayableValue()} but it was ${
                        sampleData?.displayableValue().takeUnless { it.isNullOrEmpty() } ?: "\"\""
                    }"
                    Result.Failure(errorMessage, failureReason = FailureReason.DiscriminatorMismatch)
                } else
                    mismatchResult(pattern, sampleData, resolver.mismatchMessages)
            }
        }
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        if(otherPattern !is ExactValuePattern || this.pattern != otherPattern.pattern)
            return Result.Failure("Expected ${this.typeName}, got ${otherPattern.typeName}")

        return Result.Success()
    }

    override fun fitsWithin(otherPatterns: List<Pattern>, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val results = otherPatterns.map { it.matches(pattern, otherResolver) }
        return results.find { it is Result.Success } ?: results.firstOrNull() ?: Result.Failure("No matching patterns.")
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return pattern.listOf(valueList)
    }

    override fun generate(resolver: Resolver) = pattern
    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))
    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)
    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        val nullPattern: ReturnValue<Pattern> = HasValue(NullPattern)
        return sequenceOf(nullPattern).plus(pattern.type().negativeBasedOn(Row(), resolver).filterValueIsNot { it == NullPattern })
    }

    override fun parse(value: String, resolver: Resolver): Value = pattern.type().parse(value, resolver)

    override val typeName: String = pattern.displayableValue()

    override fun toString(): String = pattern.toStringLiteral()
}
