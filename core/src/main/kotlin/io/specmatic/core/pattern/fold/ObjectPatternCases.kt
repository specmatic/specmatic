package io.specmatic.core.pattern.fold

import io.specmatic.core.KeyWithPattern
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.AdditionalProperties
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.Value

data class ObjectPropertyPattern(val name: String, val pattern: Pattern, val required: Boolean)
data class ObjectPatternCase<P : Pattern, C>(
    override val pattern: P,
    override val context: C,
    val properties: List<ObjectPropertyPattern>,
    val additionalProperties: AdditionalProperties,
) : PatternCase<P, C> {
    fun patternForProperty(name: String, value: Value? = null): Pattern? {
        return properties.firstOrNull { it.name == name }?.pattern ?: when (val additional = additionalProperties) {
            AdditionalProperties.NoAdditionalProperties -> null
            AdditionalProperties.FreeForm -> value?.deepPattern()
            is AdditionalProperties.PatternConstrained -> additional.pattern
        }
    }

    fun patternAndResolverForProperty(name: String, value: Value, resolver: Resolver): PatternAndResolver? {
        val childPattern = patternForProperty(name, value) ?: return null
        val childResolver = resolver.updateLookupPath(pattern.typeAlias, KeyWithPattern(name, childPattern))
        return PatternAndResolver(childPattern, childResolver)
    }

    fun <R> projectProperties(visitor: PatternVisitor<C, R>): List<R> {
        return properties.map { property ->
            property.pattern.accept(visitor, visitor.contextForProperty(context, property.name))
        }
    }

    fun <R> projectPropertyValue(visitor: PatternVisitor<C, R>, name: String, value: Value? = null): R? {
        return patternForProperty(name, value)?.accept(visitor, visitor.contextForProperty(context, name))
    }
}
