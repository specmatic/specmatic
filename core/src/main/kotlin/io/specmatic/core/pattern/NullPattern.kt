package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.mismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

const val NULL_TYPE = "(null)"

object NullPattern : Pattern, ScalarType {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasTemplate() == true)
            return Result.Success()

        return when {
            sampleData is NullValue -> Result.Success()
            sampleData is StringValue && sampleData.string.isEmpty() -> Result.Success()
            else -> mismatchResult("null", sampleData, resolver.mismatchMessages)
        }
    }

    override fun generate(resolver: Resolver): Value = NullValue

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))
    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)
    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return newBasedOn(row, resolver).map { it.value }.map { HasValue(it) }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        if (resolver.isNegative) return NullValue
        return when(value.trim()) {
            NULL_TYPE -> NullValue
            "" -> NullValue
            else -> throw ContractException("Failed to parse $value: it is not null.")
        }
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override fun toNullable(defaultValue: String?): Pattern {
        return this
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String = "null"

    override val pattern: Any = NULL_TYPE
    override fun toString(): String = NULL_TYPE
}

internal fun isOptionalValuePattern(patternSpec: String): Boolean = withoutPatternDelimiters(patternSpec.trim()).endsWith("?")
internal fun withoutNullToken(patternSpec: String): String {
    return "(" + withoutPatternDelimiters(patternSpec.trim()).trim().removeSuffix("?") + ")"
}