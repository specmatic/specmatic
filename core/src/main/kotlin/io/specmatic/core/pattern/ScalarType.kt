package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.matchers.Matcher
import io.specmatic.core.value.ScalarValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

interface ScalarType: Pattern {
    override fun patternFrom(value: Value, resolver: Resolver): Pattern {
        if(value !is ScalarValue) return value.exactMatchElseType()
        if(value is StringValue && isPatternToken(value)) return DeferredPattern(value.string)
        if(value is StringValue && isMatcherToken(value)) {
            return Matcher.patternFrom(
                value = value,
                originalPattern = this,
                resolver = resolver,
            )
        }
        return value.exactMatchElseType()
    }
}

fun scalarAnnotation(pattern: Pattern, negativePatterns: Sequence<Pattern>): Sequence<ReturnValue<Pattern>> {
    return negativePatterns.map {
        HasValue(it, "is mutated from ${pattern.typeName} to ${it.typeName}")
    }
}

fun scalarAnnotation(pattern: Pattern, negativePattern: Pattern): ReturnValue<Pattern> {
    return HasValue(negativePattern, "is mutated from ${pattern.typeName} to ${negativePattern.typeName}")
}
