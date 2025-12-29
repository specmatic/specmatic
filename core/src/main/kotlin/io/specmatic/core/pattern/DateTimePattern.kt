package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

object DateTimePattern : Pattern, ScalarType {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasTemplate() == true)
            return Result.Success()

        return when (sampleData) {
            is StringValue -> resultOf(ruleViolation = StandardRuleViolation.TYPE_MISMATCH) {
                parse(sampleData.string, resolver)
                Result.Success()
            }
            else -> dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)
        }
    }

    override fun generate(resolver: Resolver): StringValue = StringValue(RFC3339.currentDateTime())

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun newBasedOn(resolver: Resolver): Sequence<DateTimePattern> = sequenceOf(this)

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return scalarAnnotation(this, sequenceOf(StringPattern(), NumberPattern(), BooleanPattern(), NullPattern))
    }

    override fun parse(value: String, resolver: Resolver): StringValue = attemptParse(this, value, resolver.mismatchMessages) {
        RFC3339.parse(value)
        StringValue(value)
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String = "datetime"

    override val pattern = "(datetime)"

    override fun toString() = pattern
}
