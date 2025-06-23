package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Substitution
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

interface Pattern {
    fun matches(sampleData: Value?, resolver: Resolver): Result
    fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        val sample = sampleData.firstOrNull() ?: return ConsumeResult(Result.Failure("No data found. There should have been at least one."), emptyList())

        val result = this.matches(sample, resolver)

        return ConsumeResult(result, sampleData.drop(1))
    }

    fun generate(resolver: Resolver): Value
    fun generateWithAll(resolver: Resolver) = resolver.withCyclePrevention(this, this::generate)
    fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>>
    fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration = NegativePatternConfiguration()
    ): Sequence<ReturnValue<Pattern>>
    fun newBasedOn(resolver: Resolver): Sequence<Pattern>
    fun parse(value: String, resolver: Resolver): Value

    fun patternSet(resolver: Resolver): List<Pattern> = listOf(this)

    fun parseToType(valueString: String, resolver: Resolver): Pattern {
        return parse(valueString, resolver).exactMatchElseType()
    }

    fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack = emptySet()): Result
    fun encompasses(others: List<Pattern>, thisResolver: Resolver, otherResolver: Resolver, lengthError: String, typeStack: TypeStack = emptySet()): ConsumeResult<Pattern, Pattern> {
        val otherOne = others.firstOrNull()
                ?: return ConsumeResult(Result.Failure(lengthError), emptyList())

        val result = when {
            otherOne is ExactValuePattern && otherOne.pattern is StringValue -> ExactValuePattern(this.parse(otherOne.pattern.string, thisResolver))
            else -> otherOne
        }.let { otherOneAdjustedForExactValue -> this.encompasses(otherOneAdjustedForExactValue, thisResolver, otherResolver, typeStack) }

        return ConsumeResult(result, others.drop(1))
    }

    fun fitsWithin(otherPatterns: List<Pattern>, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val myPatternSet = patternSet(thisResolver)

        val result = myPatternSet.map { myPattern ->
            val encompassResult = otherPatterns.asSequence().map { otherPattern ->
                biggerEncompassesSmaller(otherPattern, myPattern, thisResolver, otherResolver, typeStack)
            }

            encompassResult.find { it is Result.Success } ?: encompassResult.first()
        }

        return result.find { it is Result.Failure } ?: Result.Success()
    }

    fun listOf(valueList: List<Value>, resolver: Resolver): Value

    fun toNullable(defaultValue: String?): Pattern {
        val patterns = listOf(NullPattern, this)
        return AnyPattern(patterns, example = defaultValue, extensions = patterns.extractCombinedExtensions())
    }

    fun resolveSubstitutions(substitution: Substitution, value: Value, resolver: Resolver, key: String? = null): ReturnValue<Value> {
        return substitution.substitute(value, this, key)
    }

    fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        return if(value is StringValue && value.string.startsWith("{{@") && value.string.endsWith("}}"))
            HasValue(mapOf(key to this))
        else
            HasValue(emptyMap())
    }

    fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean = false): ReturnValue<Value> {
        return fillInTheBlanksWithPattern(value, resolver, this)
    }

    fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String? = null): Pattern {
        return concretePattern
    }

    fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        return value
    }

    fun fixValue(value: Value, resolver: Resolver): Value {
        return fixValue(value, this, resolver)
    }

    val typeAlias: String?
    val typeName: String
    val pattern: Any
}

fun fillInTheBlanksWithPattern(value: Value, resolver: Resolver, self: Pattern): ReturnValue<Value> {
    val resolvedPattern = when (val resolvedPattern = resolveToPattern(value, resolver, self)) {
        is ReturnFailure -> return resolvedPattern.cast()
        else -> resolvedPattern.value
    }

    return when {
        isPatternToken(value) -> runCatching { resolver.generate(resolvedPattern) }.map(::HasValue).getOrElse(::HasException)
        resolver.isNegative -> HasValue(value)
        else -> resolvedPattern.matches(value, resolver).toReturnValue(value)
    }
}

fun resolveToPattern(value: Value, resolver: Resolver, self: Pattern): ReturnValue<Pattern> {
    if (value !is StringValue || !value.isPatternToken()) return HasValue(self)

    return runCatching {
        val pattern = resolver.getPattern(value.string)
        if (pattern is AnyValuePattern) return@runCatching HasValue(self)
        if (resolver.isNegative) return@runCatching HasValue(pattern)
        self.encompasses(pattern, resolver, resolver).toReturnValue(pattern)
    }.getOrElse(::HasException)
}

fun Pattern.isDiscriminator(): Boolean {
    return this is ExactValuePattern && this.discriminator
}

fun fillInIfPatternToken(value: Value, pattern: Pattern, resolver: Resolver): ReturnValue<Value> {
    if (value !is StringValue || !value.isPatternToken()) return HasValue(value)
    return runCatching { pattern.fillInTheBlanks(StringValue("(anyvalue)"), resolver) }.getOrElse(::HasException)
}

fun fixValue(value: Value, pattern: Pattern, resolver: Resolver): Value {
    return value.takeIf { resolver.matchesPattern(null, pattern, value).isSuccess() } ?: resolver.generate(pattern)
}
