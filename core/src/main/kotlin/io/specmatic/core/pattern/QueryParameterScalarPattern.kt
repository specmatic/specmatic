package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Substitution
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.value.*

data class QueryParameterScalarPattern(override val pattern: Pattern): Pattern by pattern, ScalarType {
    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        return scalarResolveSubstitutions(substitution, value, key, this, resolver)
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData == null) return dataTypeMismatchResult("scalar", sampleData, resolver.mismatchMessages)
        val sampleDataString = when (sampleData) {
            is ListValue -> {
                if (sampleData.list.size > 1) return dataTypeMismatchResult("scalar", sampleData, resolver.mismatchMessages)
                sampleData.list.single().toStringLiteral()
            }
            else -> sampleData.toStringLiteral()
        }

        val parsedValue = runCatching { pattern.parse(sampleDataString, resolver) }.getOrDefault(StringValue(sampleDataString))
        return resolver.matchesPattern(null, pattern, parsedValue)
    }

    override fun generate(resolver: Resolver): Value {
        return pattern.generate(resolver)
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return  pattern.parse(value, resolver)
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        return fixValue(value, this, resolver)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        if(otherPattern !is QueryParameterScalarPattern)
            return Result.Failure(thisResolver.mismatchMessages.mismatchMessage(this.typeName, otherPattern.typeName))

        return this.pattern.encompasses(otherPattern.pattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String
        get() = "(queryParameterScalar/${pattern.typeName})"

    override fun parseToType(valueString: String, resolver: Resolver): Pattern {
        return pattern.parse(valueString, resolver).exactMatchElseType()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is QueryParameterScalarPattern) return false
        return pattern == other.pattern
    }

    override fun hashCode(): Int {
        return pattern.hashCode()
    }
}