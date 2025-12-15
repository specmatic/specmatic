package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

private fun validEnumValues(values: List<Value>, key: String?, typeAlias: String?, example: String?, nullable: Boolean, multiType: Boolean): AnyPattern {
    validateEnumValues(values, nullable, multiType)
    val sortedValues = values.sortedWith(compareBy { it is StringValue })
    val patterns = sortedValues.map { ExactValuePattern(it) }
    return AnyPattern(
        patterns,
        key,
        typeAlias,
        example,
        extensions = patterns.extractCombinedExtensions()
    )
}

fun not(boolean: Boolean) = !boolean
fun validateEnumValues(values: List<Value>, enumIsNullable: Boolean, multiType: Boolean) {
    val enumOptionsContainNull = values.any { it is NullValue }
    if (not(enumIsNullable) yet enumOptionsContainNull) throw ContractException("Enum values cannot be null as the enum is not nullable")
    if (enumIsNullable yet not(enumOptionsContainNull)) throw ContractException("Enum values must contain null as the enum is nullable")
    if (multiType) return

    val types = values.filterNot { it is NullValue }.map { it.displayableType() }
    val distinctTypes = types.distinct()
    if (distinctTypes.size > 1) throw ContractException("""
    One or more enum values do not match the specified type, Found types: ${distinctTypes.joinToString(", ")}
    """.trimIndent())
}

private infix fun Boolean.yet(otherBooleanValue: Boolean): Boolean {
    return this && otherBooleanValue
}

data class EnumPattern(override val pattern: AnyPattern, val nullable: Boolean) : Pattern by pattern, ScalarType {
    constructor(
        values: List<Value>,
        key: String? = null,
        typeAlias: String? = null,
        example: String? = null,
        nullable: Boolean = false,
        multiType: Boolean = false
    ) : this (validEnumValues(values, key, typeAlias, example, nullable, multiType), nullable)

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        return scalarResolveSubstitutions(substitution, value, key, this, resolver)
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData is StringValue && (sampleData.hasTemplate() || sampleData.hasDataTemplate())) {
            return Result.Success()
        }

        return pattern.matches(sampleData, resolver)
    }

    fun withExample(example: String?): EnumPattern {
        return this.copy(pattern = pattern.copy(example = example))
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        return pattern.newBasedOn(row, resolver).map {
            it.ifHasValue {
                HasValue(it.value, "is set to '${it.value}' from enum")
            }
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        if (!config.withDataTypeNegatives) return emptySequence()
        if (nullable && pattern.pattern.size == 1) {
            return pattern.pattern.single().negativeBasedOn(row, resolver).map { it: ReturnValue<Pattern> ->
                it.ifHasValue {scalarAnnotation(this@EnumPattern, it.value)}
            }
        }

        val enumValues = pattern.pattern.mapNotNull { (it as? ExactValuePattern)?.pattern }.toSet()
        val enumDataTypes = enumValues.map { it.deepPattern() }.toSet()
        return pattern.negativeBasedOn(row, resolver).filter { negativePattern: ReturnValue<Pattern> ->
            if (negativePattern !is HasValue) return@filter true
            when (val candidate = negativePattern.value) {
                is ExactValuePattern -> candidate.pattern !in enumValues
                else -> candidate !in enumDataTypes
            }
        }.distinctBy { it: ReturnValue<Pattern> ->
            if (it !is HasValue) return@distinctBy it
            when (val candidate = it.value) {
                is ExactValuePattern -> candidate.pattern.deepPattern()
                else -> candidate
            }
        }.map { it: ReturnValue<Pattern> ->
            it.ifHasValue {
                scalarAnnotation(this@EnumPattern, it.value)
            }
        }
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        val patternToConsider = when (val resolvedPattern = resolveToPattern(value, resolver, this)) {
            is ReturnFailure -> return resolvedPattern.cast()
            else -> resolvedPattern.value
        }

        return if (isPatternToken(value) && patternToConsider == this) HasValue(resolver.generate(this))
        else pattern.fillInTheBlanks(value, resolver, removeExtraKeys)
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        return fixValue(value, this, resolver)
    }

    override fun equals(other: Any?): Boolean = other is EnumPattern && other.pattern == this.pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun toNullable(defaultValue: String?): EnumPattern {
        if (nullable) return this
        return copy(nullable = true, pattern = pattern.copy(pattern = pattern.pattern.plus(ExactValuePattern(NullValue))))
    }
}