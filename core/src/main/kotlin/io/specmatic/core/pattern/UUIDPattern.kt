package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.util.*

object UUIDPattern : Pattern, ScalarType {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData?.hasTemplate() == true)
            return Result.Success()

        return when (sampleData) {
            is StringValue -> resultOf {
                parse(sampleData.string, resolver)
                Result.Success()
            }

            else -> Result.Failure("UUID types can only be represented using strings")
        }
    }

    override fun generate(resolver: Resolver): StringValue = StringValue(UUID.randomUUID().toString())

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun newBasedOn(resolver: Resolver): Sequence<UUIDPattern> = sequenceOf(this)

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return scalarAnnotation(this, sequenceOf(NullPattern))
    }

    override fun parse(value: String, resolver: Resolver): StringValue =
        attempt {
            UUID.fromString(value)
            StringValue(value)
        }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack,
    ): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String?
        get() = null
    override val typeName: String = "uuid"
    override val pattern = "(uuid)"

    override fun toString() = pattern
}
