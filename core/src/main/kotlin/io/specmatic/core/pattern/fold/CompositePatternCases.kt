package io.specmatic.core.pattern.fold

import io.specmatic.core.Resolver
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.fold.Item
import io.specmatic.core.value.fold.XmlAttribute

data class DelegatedPatternCase<P : Pattern, D : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val delegate: D,
    private val delegateClass: Class<D>,
    private val rewrapPattern: (D) -> P,
) : PatternCase<P, C> {
    fun rewrap(delegate: D): P = rewrapPattern(delegate)

    fun rewrapProjected(delegate: Pattern): P? {
        if (!delegateClass.isInstance(delegate)) return null
        return rewrap(delegateClass.cast(delegate))
    }

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
    private val rebuildPattern: (List<XmlAttribute<Pattern>>, List<Item<Pattern>>) -> P,
) : PatternCase<P, C> {
    fun rebuild(attributes: List<XmlAttribute<Pattern>>, children: List<Item<Pattern>>): P {
        return rebuildPattern(attributes, children)
    }

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
