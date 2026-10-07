package io.specmatic.core.pattern.fold

import io.specmatic.core.pattern.Pattern

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
}
