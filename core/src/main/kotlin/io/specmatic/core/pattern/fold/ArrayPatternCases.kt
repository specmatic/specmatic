package io.specmatic.core.pattern.fold

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.fold.Item

data class IndexedListPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val items: List<Item<Pattern>>,
) : PatternCase<P, C> {
    fun patternFor(index: Int): Pattern? = items.firstOrNull { it.index == index }?.value

    fun <R> projectItems(visitor: PatternVisitor<C, R>): List<Item<R>> = items.map { item ->
        Item(
            index = item.index,
            value = item.value.accept(visitor, visitor.contextForIndex(context, item.index)),
        )
    }
}

data class RepeatedListPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val itemPattern: Pattern,
) : PatternCase<P, C> {
    fun <R> projectItem(visitor: PatternVisitor<C, R>, index: Int): R {
        return itemPattern.accept(visitor, visitor.contextForIndex(context, index))
    }
}
