package io.specmatic.core.pattern

interface ScalarType: Pattern

fun scalarAnnotation(pattern: Pattern, negativePatterns: Sequence<Pattern>): Sequence<ReturnValue<Pattern>> {
    return negativePatterns.map {
        HasValue(it, "is mutated from ${pattern.typeName} to ${it.typeName}")
    }
}


