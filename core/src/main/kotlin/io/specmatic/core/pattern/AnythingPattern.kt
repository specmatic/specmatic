package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

//TODO: Does this include Null?
object AnythingPattern: Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return Result.Success()
    }

    override fun generate(resolver: Resolver): Value {
        return StringValue(randomString(10))
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return sequenceOf(this)
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return sequenceOf(HasValue(this))
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
        return when(otherPattern) {
            AnythingPattern -> Result.Success()
            else -> Result.Failure("Changing from anyType to ${otherPattern.typeName} is a breaking change.")
        }
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String? = null

    override val typeName: String = "anything"

    override val pattern: Any = "(anything)"
}
