package io.specmatic.core.pattern

interface ScalarType

fun scalarAnnotation(pattern: Pattern, negativePatterns: Sequence<Pattern>): Sequence<ReturnValue<Pattern>> {
    return negativePatterns.map {
        HasValue(it, "${pattern.typeName} mutated to ${it.typeName}")
    }
}


