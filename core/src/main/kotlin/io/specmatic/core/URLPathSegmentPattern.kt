package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class URLPathSegmentPattern(override val pattern: Pattern, override val key: String? = null, override val typeAlias: String? = null, val conflicts: Set<String> = emptySet()) : Pattern, Keyed {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        val result = resolver.matchesPattern(key, pattern, sampleData ?: NullValue)
        if (result.isSuccess() && sampleData?.toStringLiteral() in conflicts) {
            return Result.Failure(
                "Value ${sampleData?.displayableValue()} conflicts with an existing path using the same prefix",
                failureReason = FailureReason.URLPathParamMatchButConflict
            )
        }

        return result
    }

    override fun generate(resolver: Resolver): Value {
        return resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            if (key != null)
                cyclePreventedResolver.generate(key, pattern)
            else pattern.generate(cyclePreventedResolver)
        }
    }

    fun newBasedOnWrapper(row: Row, resolver: Resolver): Sequence<Pattern> {
        return newBasedOn(row, resolver).map { it.value }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.newBasedOn(row, cyclePreventedResolver).map { it.ifValue { URLPathSegmentPattern(it, key) } }
        }

    override fun newBasedOn(resolver: Resolver): Sequence<URLPathSegmentPattern> =
        resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.newBasedOn(cyclePreventedResolver).map { URLPathSegmentPattern(it, key) }
        }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        if(pattern is ExactValuePattern)
            return emptySequence()

        if(pattern is StringPattern)
            return emptySequence()

        return resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            pattern.negativeBasedOn(row, cyclePreventedResolver).map {
                it.breadCrumb(BreadCrumb.PARAM_PATH.with(key))
            }.filterValueIsNot {
                it is NullPattern
            }.map {
                it.ifValue {  URLPathSegmentPattern(it, key) }
            }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value = pattern.parse(value, resolver)

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        if(otherPattern !is URLPathSegmentPattern)
            return this.pattern.encompasses(otherPattern, thisResolver, otherResolver, typeStack)

        return otherPattern.pattern.fitsWithin(patternSet(thisResolver), otherResolver, thisResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    fun tryParse(token: String, resolver: Resolver): Value {
        return try {
            this.pattern.parse(token, resolver)
        } catch (e: Throwable) {
            if (isPatternToken(token) && token.contains(":"))
                StringValue(withPatternDelimiters(withoutPatternDelimiters(token).split(":".toRegex(), 2)[1]))
            else
                StringValue(token)
        }
    }

    override val typeName: String = "url path"
}