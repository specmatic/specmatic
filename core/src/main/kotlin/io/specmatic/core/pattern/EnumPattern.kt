package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

private fun validEnumValues(values: List<Value>, key: String?, typeAlias: String?, example: String?, nullable: Boolean): AnyPattern {
    assertThatAllValuesHaveTheSameType(values, nullable)
    val patterns = values.map { ExactValuePattern(it) }
    return AnyPattern(
        patterns,
        key,
        typeAlias,
        example,
        extensions = patterns.extractCombinedExtensions()
    )
}

fun not(boolean: Boolean) = !boolean
fun assertThatAllValuesHaveTheSameType(values: List<Value>, enumIsNullable: Boolean) {
    val enumOptionsContainNull = values.any { it is NullValue }

    if(not(enumIsNullable) yet enumOptionsContainNull)
        throw ContractException("Enum values cannot be null as the enum is not nullable")

    val types = values.filterNot { it is NullValue }.map { it.javaClass }
    val distinctTypes = types.distinct()

    if(distinctTypes.size > 1)
        throw ContractException("Enum values must all be of the same type. Found types: ${distinctTypes.joinToString(", ")}")
}

private infix fun Boolean.yet(otherBooleanValue: Boolean): Boolean {
    return this && otherBooleanValue
}

data class EnumPattern(
    override val pattern: AnyPattern,
    val nullable: Boolean
) : Pattern by pattern, ScalarType {
    constructor(values: List<Value>,
                key: String? = null,
                typeAlias: String? = null,
                example: String? = null,
                nullable: Boolean = false
    ) : this(validEnumValues(values, key, typeAlias, example, nullable), nullable)

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
                HasValue(it.value, "selected ${it.value} from enum")
            }
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        val current = this
        return sequence {
            if(config.withDataTypeNegatives) {
                yieldAll(scalarAnnotation(current, pattern.negativeBasedOn(row, resolver).map { it.value }))
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

    override fun toNullable(defaultValue: String?): Pattern {
        return this
    }
}