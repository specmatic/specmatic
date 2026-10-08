package io.specmatic.core.pattern

import io.specmatic.core.pattern.fold.PatternVisitor
import io.specmatic.core.pattern.fold.ScalarPatternCase

interface ScalarType : Pattern {
    override fun <C, R> accept(visitor: PatternVisitor<C, R>, context: C): R {
        return visitor.scalar(ScalarPatternCase(pattern = this, context = context))
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
