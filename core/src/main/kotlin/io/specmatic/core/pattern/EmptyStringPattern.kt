package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.valueMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.*

object EmptyStringPattern : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (sampleData) {
            EmptyString -> Result.Success()
            is StringValue -> valueMismatchResult("empty string", sampleData, resolver.mismatchMessages)
            else -> dataTypeMismatchResult("empty string", sampleData, resolver.mismatchMessages)
        }
    }

    override fun generate(resolver: Resolver): Value = StringValue("")
    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))
    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)
    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return sequenceOf(HasValue(this))
    }

    override fun parse(value: String, resolver: Resolver): Value = attemptParse("empty string", value, resolver.mismatchMessages) {
        when {
            value.isEmpty() -> EmptyString
            else -> throw ContractException("No data was expected")
        }
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        if (otherPattern is EmptyStringPattern) return Result.Success()
        return Result.Failure(message = "No data was expected, but got \"${otherPattern.typeName}\" instead", ruleViolation = StandardRuleViolation.TYPE_MISMATCH)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String = "nothing"

    override val pattern: Any = ""

    override fun toString(): String = "(emptystring)"
}