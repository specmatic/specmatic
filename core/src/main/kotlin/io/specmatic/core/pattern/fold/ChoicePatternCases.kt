package io.specmatic.core.pattern.fold

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Discriminator
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.Value

fun interface PatternSelector {
    fun choose(value: Value, resolver: Resolver): PatternAndResolver?
}

data class OneOfPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val alternatives: List<Pattern>,
    private val selector: PatternSelector,
    val discriminator: Discriminator? = null,
    private val rebuildPattern: (List<Pattern>) -> P,
) : PatternCase<P, C> {
    fun rebuild(alternatives: List<Pattern>): P = rebuildPattern(alternatives)

    fun choosePattern(value: Value, resolver: Resolver): Pattern? = selector.choose(value, resolver)?.pattern

    fun choosePatternAndResolver(value: Value, resolver: Resolver): PatternAndResolver? = selector.choose(value, resolver)

    fun <R> projectAlternatives(visitor: PatternVisitor<C, R>): List<R> {
        return alternatives.mapIndexed { index, alternative ->
            alternative.accept(visitor, visitor.contextForAlternative(context, index))
        }
    }
}

data class AnyOfPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val alternatives: List<Pattern>,
    private val selector: PatternSelector,
    val discriminator: Discriminator? = null,
    private val rebuildPattern: (List<Pattern>) -> P,
) : PatternCase<P, C> {
    fun rebuild(alternatives: List<Pattern>): P = rebuildPattern(alternatives)

    /** Selects one branch when an operation needs a single choice; [alternatives] retains every AnyOf branch. */
    fun choosePattern(value: Value, resolver: Resolver): Pattern? = selector.choose(value, resolver)?.pattern

    fun choosePatternAndResolver(value: Value, resolver: Resolver): PatternAndResolver? = selector.choose(value, resolver)

    fun <R> projectAlternatives(visitor: PatternVisitor<C, R>): List<R> {
        return alternatives.mapIndexed { index, alternative ->
            alternative.accept(visitor, visitor.contextForAlternative(context, index))
        }
    }
}

data class AlternativeSequencesPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val alternatives: List<List<Pattern>>,
    private val rebuildPattern: (List<List<Pattern>>) -> P,
) : PatternCase<P, C> {
    fun rebuild(alternatives: List<List<Pattern>>): P = rebuildPattern(alternatives)

    fun <R> projectAlternatives(visitor: PatternVisitor<C, R>): List<List<R>> {
        return alternatives.mapIndexed { alternativeIndex, alternative ->
            val alternativeContext = visitor.contextForAlternative(context, alternativeIndex)
            alternative.mapIndexed { index, pattern ->
                pattern.accept(visitor, visitor.contextForIndex(alternativeContext, index))
            }
        }
    }
}
