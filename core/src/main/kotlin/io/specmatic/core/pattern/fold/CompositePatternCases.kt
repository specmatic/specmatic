package io.specmatic.core.pattern.fold

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.fold.Item
import io.specmatic.core.value.fold.XmlAttribute

data class DelegatedPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val delegate: Pattern,
) : PatternCase<P, C> {
    fun <R> projectDelegate(visitor: PatternVisitor<C, R>): R = delegate.accept(visitor, context)
}

data class DeferredPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    private val resolvePattern: (Resolver) -> Pattern,
) : PatternCase<P, C> {
    fun resolve(resolver: Resolver): Pattern = resolvePattern(resolver)

    fun <R> resolveAndVisit(visitor: PatternVisitor<C, R>, resolver: Resolver): R {
        return resolve(resolver).accept(visitor, context)
    }
}

data class XmlElementPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val attributes: List<XmlAttribute<Pattern>>,
    val children: List<Item<Pattern>>,
) : PatternCase<P, C> {
    fun <R> projectAttributes(visitor: PatternVisitor<C, R>): List<XmlAttribute<R>> = attributes.map { attribute ->
        XmlAttribute(
            name = attribute.name,
            value = attribute.value.accept(visitor, visitor.contextForXmlAttribute(context, attribute.name)),
        )
    }

    fun <R> projectChildren(visitor: PatternVisitor<C, R>): List<Item<R>> = children.map { child ->
        Item(
            index = child.index,
            value = child.value.accept(visitor, visitor.contextForXmlChild(context, child.index)),
        )
    }
}
