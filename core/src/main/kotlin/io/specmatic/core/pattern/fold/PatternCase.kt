package io.specmatic.core.pattern.fold

import io.specmatic.core.KeyWithPattern
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.Value

data class PatternAndResolver(val pattern: Pattern, val resolver: Resolver)

sealed interface PatternCase<out P : Pattern, out C> {
    val pattern: P
    val context: C
}

data class ScalarPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
) : PatternCase<P, C>

data class TextPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
) : PatternCase<P, C>

data class ConstantPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
) : PatternCase<P, C>

data class OpaquePatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
) : PatternCase<P, C> {
    val kind: String = pattern::class.simpleName ?: "Pattern"

    fun patternAndResolverForProperty(name: String, value: Value, resolver: Resolver): PatternAndResolver {
        val childPattern = value.deepPattern()
        val childResolver = resolver.updateLookupPath(pattern.typeAlias, KeyWithPattern(name, childPattern))
        return PatternAndResolver(childPattern, childResolver)
    }

    fun patternAndResolverForArrayItem(value: Value, resolver: Resolver): PatternAndResolver {
        val childPattern = value.deepPattern()
        val childResolver = resolver.updateLookupPathForArrayItem(pattern, childPattern)
        return PatternAndResolver(childPattern, childResolver)
    }
}
