package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.Value

data class QueryParameterObjectPattern(
    override val pattern: Pattern,
    val excludedRootProperties: Set<String>
) : Pattern, PossibleJsonObjectPatternContainer {
    fun excludingRootProperties(propertyNames: Set<String>): QueryParameterObjectPattern {
        return copy(excludedRootProperties = excludedRootProperties + propertyNames)
    }

    private fun projectedPattern(resolver: Resolver): Pattern {
        return pattern.withoutRootProperties(excludedRootProperties, resolver)
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return projectedPattern(resolver).matches(sampleData, resolver)
    }

    override fun generate(resolver: Resolver): Value {
        return projectedPattern(resolver).generate(resolver)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        return projectedPattern(resolver).newBasedOn(row, resolver)
    }

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> {
        return projectedPattern(resolver).negativeBasedOn(row, resolver, config)
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return projectedPattern(resolver).newBasedOn(resolver)
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return projectedPattern(resolver).parse(value, resolver)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val otherProjectedPattern = when (otherPattern) {
            is QueryParameterObjectPattern -> otherPattern.projectedPattern(otherResolver)
            else -> otherPattern
        }

        return projectedPattern(thisResolver).encompasses(otherProjectedPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return projectedPattern(resolver).listOf(valueList, resolver)
    }

    override fun patternSet(resolver: Resolver): List<Pattern> {
        return projectedPattern(resolver).patternSet(resolver)
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        return projectedPattern(resolver).resolveSubstitutions(substitution, value, resolver, key)
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        return projectedPattern(resolver).getTemplateTypes(key, value, resolver)
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        return projectedPattern(resolver).fillInTheBlanks(value, resolver, removeExtraKeys)
    }

    override fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String?): Pattern {
        return projectedPattern(resolver).addTypeAliasesToConcretePattern(concretePattern, resolver, typeAlias)
    }

    override fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        return projectedPattern(resolver).eliminateOptionalKey(value, resolver)
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        return projectedPattern(resolver).fixValue(value, resolver)
    }

    override fun patternFrom(value: Value, resolver: Resolver, parseValueToType: (Value) -> Pattern): Pattern {
        return projectedPattern(resolver).patternFrom(value, resolver, parseValueToType)
    }

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        val projectedPattern = projectedPattern(resolver)
        return when (projectedPattern) {
            is PossibleJsonObjectPatternContainer -> projectedPattern.removeKeysNotPresentIn(keys, resolver)
            else -> projectedPattern
        }
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern? {
        return projectedPattern(resolver).asJsonObjectPattern(resolver)
    }

    override fun ensureAdditionalProperties(resolver: Resolver): Pattern {
        return projectedPattern(resolver).withAdditionalPropertiesEnsured(resolver)
    }

    override val extensions: Map<String, Any>
        get() = (pattern as? PossibleJsonObjectPatternContainer)?.extensions.orEmpty()

    override val typeAlias: String?
        get() = pattern.typeAlias

    override val typeName: String
        get() = pattern.typeName
}

private fun Pattern.withoutRootProperties(propertyNames: Set<String>, resolver: Resolver): Pattern {
    if (propertyNames.isEmpty()) return this

    return when (this) {
        is QueryParameterObjectPattern -> excludingRootProperties(propertyNames)
        is QueryParameterScalarPattern -> QueryParameterScalarPattern(pattern.withoutRootProperties(propertyNames, resolver))
        is JSONObjectPattern -> withoutRootProperties(propertyNames)
        is PossibleJsonObjectPatternContainer -> this.jsonObjectPattern(resolver)?.withoutRootProperties(propertyNames) ?: this
        else -> this
    }
}

private fun JSONObjectPattern.withoutRootProperties(propertyNames: Set<String>): JSONObjectPattern {
    return copy(pattern = pattern.filterKeys { key -> withoutOptionality(key) !in propertyNames })
}

private fun Pattern.asJsonObjectPattern(resolver: Resolver): JSONObjectPattern? {
    return when (this) {
        is QueryParameterScalarPattern -> pattern.asJsonObjectPattern(resolver)
        is JSONObjectPattern -> this
        is PossibleJsonObjectPatternContainer -> this.jsonObjectPattern(resolver)
        else -> null
    }
}

private fun Pattern.withAdditionalPropertiesEnsured(resolver: Resolver): Pattern {
    return when (this) {
        is QueryParameterScalarPattern -> QueryParameterScalarPattern(pattern.withAdditionalPropertiesEnsured(resolver))
        is PossibleJsonObjectPatternContainer -> this.ensureAdditionalProperties(resolver)
        else -> this
    }
}
