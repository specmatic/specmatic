package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Substitution
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.Value
import io.specmatic.core.valueMismatchResult

data class AnyNonNullJSONValue(override val pattern: Pattern = AnythingPattern): Pattern by pattern{
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData is NullValue) return valueMismatchResult("non-null value", sampleData, resolver.mismatchMessages)
        return Result.Success()
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        return scalarResolveSubstitutions(substitution, value, key, this, resolver)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return when(otherPattern) {
            AnyNonNullJSONValue() -> Result.Success()
            else -> Result.Failure("Changing from anyType to ${otherPattern.typeName} is a breaking change.")
        }
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        return fillInTheBlanksWithPattern(value, resolver, this)
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        return value.takeIf { this.matches(it, resolver).isSuccess() } ?: resolver.generate(pattern)
    }
}
