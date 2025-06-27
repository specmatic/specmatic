package io.specmatic.core.pattern

interface ScalarType : HasDefaultExample

fun scalarAnnotation(pattern: Pattern, negativePatterns: Sequence<Pattern>): Sequence<ReturnValue<Pattern>> {
    return negativePatterns.map {
        HasValue(it, "${pattern.typeName} mutated to ${it.typeName}")
    }
}


