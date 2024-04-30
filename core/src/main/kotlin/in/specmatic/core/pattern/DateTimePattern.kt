package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value


object DateTimePattern : Pattern, ScalarType {
    override fun matches(sampleData: Value?, resolver: Resolver): Result = when (sampleData) {
        is StringValue -> resultOf {
            parse(sampleData.string, resolver)
            Result.Success()
        }
        else -> Result.Failure("DateTime types can only be represented using strings")
    }

    override fun generate(resolver: Resolver): StringValue = StringValue(RFC3339.currentDateTime())

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<DateTimePattern> = sequenceOf(this)

    override fun newBasedOn(resolver: Resolver): Sequence<DateTimePattern> = sequenceOf(this)

    override fun negativeBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        return scalarAnnotation(this, sequenceOf(NullPattern))
    }

    override fun parse(value: String, resolver: Resolver): StringValue =
            attempt {
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
