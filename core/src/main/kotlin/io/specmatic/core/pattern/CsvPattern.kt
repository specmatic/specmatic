package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

class CsvPattern(override val pattern: Pattern) : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is StringValue) return dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)
        val results: List<Result> = sampleData.string.split(",").mapIndexed { index, value ->
            resultOf(ruleViolation = StandardRuleViolation.VALUE_MISMATCH) {
                pattern.parse(value, resolver)
                Result.Success()
            }.breadCrumb("[$index]")
        }
        return Result.fromResults(results)
    }

    override fun generate(resolver: Resolver): Value {
        val max = (2..5).random()

        return StringValue((1..max).map {
            resolver.withCyclePrevention(pattern, pattern::generate)
        }.joinToString(",") { it.toStringLiteral() })
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return StringPattern().negativeBasedOn(row, resolver)
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return sequenceOf(this)
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return StringValue(value)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        if(otherPattern !is CsvPattern)
            return Result.Failure(thisResolver.mismatchMessages.mismatchMessage(this.typeName, otherPattern.typeName))

        return this.pattern.encompasses(otherPattern.pattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override fun toString(): String {
        return typeName
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String
        get() = "(csv/${pattern.typeName})"
}