package io.specmatic.core.pattern

import io.ktor.http.*
import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.discriminator.DiscriminatorMetadata
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.*

data class AnyPattern(
    override val pattern: List<Pattern>,
    val key: String? = null,
    override val typeAlias: String? = null,
    override val example: String? = null,
    val discriminator: Discriminator? = null
) : Pattern, HasDefaultExample, PossibleJsonObjectPatternContainer {
    constructor(
        pattern: List<Pattern>,
        key: String? = null,
        typeAlias: String? = null,
        example: String? = null,
        discriminatorProperty: String? = null,
        discriminatorValues: Set<String> = emptySet()
    ) : this(pattern, key, typeAlias, example, Discriminator.create(
        discriminatorProperty,
        discriminatorValues,
        emptyMap()
    ))

    data class AnyPatternMatch(val pattern: Pattern, val result: Result)

    fun fixValue(
        value: Value, resolver: Resolver, discriminatorValue: String,
        onValidDiscValue: () -> Value?, onInvalidDiscValue: (Failure) -> Value?
    ): Value? {
        return getDiscriminatorPattern(discriminatorValue, resolver).realise(
            hasValue = { it, _ -> it.fixValue(value, resolver) },
            orException = { _ -> onValidDiscValue() },
            orFailure = { f ->
                if (f.failure.failureReason == FailureReason.DiscriminatorMismatch) {
                    onInvalidDiscValue(f.failure)
                } else onValidDiscValue()
            }
        )
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        if (resolver.matchesPattern(null, this, value).isSuccess()) return value
        val discBasedFixedValue = if (discriminator != null && value is JSONObjectValue && discriminator.property in value.jsonObject) {
            val discriminatorValue = value.jsonObject.getValue(discriminator.property).toStringLiteral()
            fixValue(
                value = value, resolver = resolver, discriminatorValue = discriminatorValue,
                onValidDiscValue = { generateValue(resolver, discriminatorValue) },
                onInvalidDiscValue = { null }
            )
        } else null

        if (discBasedFixedValue != null) return discBasedFixedValue
        val updatedPatterns = discriminator
            ?.updatePatternsWithDiscriminator(pattern, resolver)?.listFold()
            ?.withDefault(pattern) { it } ?: pattern

        val patternMatches = updatedPatterns.map { pattern ->
            AnyPatternMatch(pattern, pattern.matches(value, resolver))
        }

        if (patternMatches.any { it.result.isSuccess() }) return value
        val matchingPatternNew = patternMatches.minBy { (it.result as? Failure)?.failureCount() ?: 0}
        return matchingPatternNew.pattern.fixValue(value, resolver)
    }

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        if(keys.isEmpty()) return this

        return this.copy(pattern = this.pattern.map {
            if (it !is PossibleJsonObjectPatternContainer) return@map it
            it.removeKeysNotPresentIn(keys, resolver)
        })
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern? {
        if (this.hasNoAmbiguousPatterns().not()) return null

        val pattern = this.pattern.first { it !is NullPattern }
        if (pattern is JSONObjectPattern) return pattern
        if (pattern is PossibleJsonObjectPatternContainer) return pattern.jsonObjectPattern(resolver)
        return null
    }

    override fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        val matchingPattern = pattern.find { it.matches(value, resolver) is Result.Success } ?: return value
        return matchingPattern.eliminateOptionalKey(value, resolver)
    }

    override fun equals(other: Any?): Boolean = other is AnyPattern && other.pattern == this.pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String?): Pattern {
        val matchingPattern = pattern.find { it.matches(concretePattern.generate(resolver), resolver) is Result.Success } ?: return concretePattern

        return matchingPattern.addTypeAliasesToConcretePattern(concretePattern, resolver, this.typeAlias ?: typeAlias)
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver): ReturnValue<Value> {
        val results = pattern.asSequence().map {
            it.fillInTheBlanks(value, resolver)
        }

        val successfulGeneration = results.firstOrNull { it is HasValue }

        if(successfulGeneration != null)
            return successfulGeneration

        val failures = results.toList().filterIsInstance<Failure>()

        return HasFailure(Failure.fromFailures(failures))
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        val options = pattern.map {
            try {
                it.resolveSubstitutions(substitution, value, resolver, key)
            } catch(e: Throwable) {
                HasException(e)
            }
        }

        val hasValue = options.find { it is HasValue }

        if(hasValue != null)
            return hasValue

        val failures = options.map {
            it.realise(
                hasValue = { _, _ ->
                    throw NotImplementedError()
                },
                orFailure = { failure -> failure.failure },
                orException = { exception -> exception.toHasFailure().failure }
            )
        }

        return HasFailure(Failure.fromFailures(failures))
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        val initialValue: ReturnValue<Map<String, Pattern>> = HasValue(emptyMap())

        return pattern.fold(initialValue) { acc, pattern ->
            val templateTypes = pattern.getTemplateTypes("", value, resolver)
            acc.assimilate(templateTypes) { data, additional -> data + additional }
        }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(discriminator != null) {
            return discriminator.matches(sampleData, pattern, key, resolver)
        }

        val matchResults: List<AnyPatternMatch> =
            pattern.map {
                AnyPatternMatch(it, resolver.matchesPattern(key, it, sampleData ?: EmptyString))
            }

        val matchResult = matchResults.find { it.result is Result.Success }

        if(matchResult != null)
            return matchResult.result

        val failures = matchResults.map { it.result }.filterIsInstance<Failure>()

        if(failures.any { it.reasonIs { it.objectMatchOccurred } }) {
            val failureMatchResults = matchResults.filter {
                it.result is Failure && it.result.reasonIs { it.objectMatchOccurred }
            }

            val objectTypeMatchedButHadSomeOtherMismatch = addTypeInfoBreadCrumbs(failureMatchResults)

            return Failure.fromFailures(objectTypeMatchedButHadSomeOtherMismatch).removeReasonsFromCauses()
        }

        val resolvedPatterns = pattern.map { resolvedHop(it, resolver) }

        if(resolvedPatterns.any { it is NullPattern } || resolvedPatterns.all { it is ExactValuePattern })
            return failedToFindAny(
                    typeName,
                    sampleData,
                    getResult(matchResults.map { it.result as Failure }),
                    resolver.mismatchMessages
                )

        val failuresWithUpdatedBreadcrumbs = addTypeInfoBreadCrumbs(matchResults)

        return Result.fromFailures(failuresWithUpdatedBreadcrumbs)
    }

    fun getUpdatedPattern(resolver: Resolver): List<Pattern> {
        return if (discriminator != null) {
            discriminator.updatePatternsWithDiscriminator(pattern, resolver).listFold().takeIf {
                it is HasValue<List<Pattern>>
            }?.value ?: return emptyList()
        } else pattern
    }

    override fun generate(resolver: Resolver): Value {
        return resolver.resolveExample(example, pattern)
            ?: generateValue(resolver)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val updatedPatterns = discriminator?.let {
            it.updatePatternsWithDiscriminator(pattern, resolver).let { updatedPatterns ->
                if(updatedPatterns.any { it !is HasValue<Pattern> }) {
                    val failures = updatedPatterns.map { pattern ->
                        when(pattern) {
                            is HasValue -> null
                            is HasFailure -> pattern.failure
                            is HasException -> pattern.toFailure()
                        }
                    }.filterNotNull()

                    return sequenceOf(HasFailure(Failure.fromFailures(failures)))
                }

                updatedPatterns.listFold().value
            }
        } ?: pattern

        resolver.resolveExample(example, updatedPatterns)?.let {
            return sequenceOf(HasValue(ExactValuePattern(it)))
        }

        val isNullable = updatedPatterns.any { it is NullPattern }
        val patternResults: Sequence<Pair<Sequence<ReturnValue<Pattern>>?, Throwable?>> =
            updatedPatterns.asSequence().sortedBy { it is NullPattern }.map { innerPattern ->
                try {
                    val patterns =
                        resolver.withCyclePrevention(innerPattern, isNullable) { cyclePreventedResolver ->
                            val row = discriminator?.removeKeyFromRow(row) ?: row
                            innerPattern.newBasedOn(row, cyclePreventedResolver).map { it.value }
                        } ?: sequenceOf()
                    Pair(patterns.map { HasValue(it) }, null)
                } catch (e: Throwable) {
                    Pair(null, e)
                }
            }

        return newTypesOrExceptionIfNone(patternResults, "Could not generate new tests")
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        val isNullable = pattern.any {it is NullPattern}
        return pattern.asSequence().flatMap { innerPattern ->
            resolver.withCyclePrevention(innerPattern, isNullable) { cyclePreventedResolver ->
                innerPattern.newBasedOn(cyclePreventedResolver)
            }?: emptySequence()  // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        val nullable = pattern.any { it is NullPattern }

        val negativeTypeResults = pattern.asSequence().map {
            try {
                val patterns: Sequence<ReturnValue<Pattern>> =
                    it.negativeBasedOn(row, resolver)
                Pair(patterns, null)
            } catch(e: Throwable) {
                Pair(null, e)
            }
        }

        val negativeTypes = newTypesOrExceptionIfNone(
            negativeTypeResults,
            "Could not get negative tests"
        ).let { patterns: Sequence<ReturnValue<Pattern>> ->
            if (nullable)
                patterns.filterValueIsNot { it is NullPattern }
            else
                patterns
        }

        return negativeTypes.distinctBy {
            it.withDefault(randomString(10)) {
                distinctableValueOnlyForScalars(it)
            }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        val resolvedTypes = pattern.map { resolvedHop(it, resolver) }
        val nonNullTypesFirst = resolvedTypes.filterNot { it is NullPattern }.plus(resolvedTypes.filterIsInstance<NullPattern>())

        return nonNullTypesFirst.asSequence().map {
            try {
                it.parse(value, resolver)
            } catch (e: Throwable) {
                null
            }
        }.find { it != null } ?: throw ContractException(
            "Failed to parse value \"$value\". It should have matched one of ${
                pattern.joinToString(
                    ", "
                ) { it.typeName }
            }."
        )
    }

    override fun patternSet(resolver: Resolver): List<Pattern> =
        this.pattern.flatMap { it.patternSet(resolver) }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val compatibleResult = otherPattern.fitsWithin(patternSet(thisResolver), otherResolver, thisResolver, typeStack)

        return if(compatibleResult is Failure && allValuesAreScalar())
            mismatchResult(this, otherPattern, thisResolver.mismatchMessages)
        else
            compatibleResult
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        if (pattern.isEmpty())
            throw ContractException("AnyPattern doesn't have any types, so can't infer which type of list to wrap the given value in")

        return pattern.first().listOf(valueList, resolver)
    }

    override val typeName: String
        get() {
            return if (pattern.size == 2 && isNullablePattern()) {
                val concreteTypeName =
                    withoutPatternDelimiters(pattern.filterNot { it is NullPattern || it.typeAlias == "(empty)" }
                        .first().typeName)
                "($concreteTypeName?)"
            } else
                "(${pattern.joinToString(" or ") { inner -> withoutPatternDelimiters(inner.typeName).let { if(it == "null") "\"null\"" else it}  }})"
        }

    override fun toNullable(defaultValue: String?): Pattern {
        return this
    }

    fun isDiscriminatorPresent() = discriminator?.isNotEmpty() == true

    fun hasMultipleDiscriminatorValues() = discriminator?.hasMultipleValues() == true

    fun generateForEveryDiscriminatorValue(resolver: Resolver): List<DiscriminatorBasedItem<Value>> {
        return discriminator?.values.orEmpty().map { discriminatorValue ->
            DiscriminatorBasedItem(
                discriminator = DiscriminatorMetadata(
                    discriminatorProperty = discriminator?.property.orEmpty(),
                    discriminatorValue = discriminatorValue,
                ),
                value = generateValue(resolver, discriminatorValue)
            )
        }
    }

    private fun getDiscriminatorPattern(discriminatorValue: String, resolver: Resolver): ReturnValue<Pattern> {
        if (discriminator == null) return HasFailure(
            Failure(
                "Pattern is not discriminator based",
                failureReason = FailureReason.DiscriminatorMismatch
            )
        )

        val discriminatorCsvClause = if(discriminator.values.size == 1) {
            discriminator.values.first()
        } else "one of ${discriminator.values.joinToString(", ")}"

        if (discriminatorValue !in discriminator.values) {
            return HasFailure(
                Failure(
                    message = "Expected the value of discriminator to be $discriminatorCsvClause but it was ${discriminatorValue.quote()}",
                    failureReason = FailureReason.DiscriminatorMismatch
                )
            )
        }

        return discriminator.updatePatternsWithDiscriminator(pattern, resolver).listFold().realise(
            hasValue = { updatedPatterns, _ ->
                val chosenPattern = getDiscriminatorBasedPattern(updatedPatterns, discriminatorValue, resolver) ?: return@realise HasFailure(
                    Failure(
                        message = "Could not find pattern with discriminator value ${discriminatorValue.quote()}",
                        failureReason = FailureReason.DiscriminatorMismatch
                    )
                )
                HasValue(chosenPattern)
            },
            orFailure = { failure -> failure.cast() },
            orException = { exception -> exception.cast() }
        )
    }

    fun matchesValue(sampleData: Value?, resolver: Resolver, discriminatorValue: String, discMisMatchBreadCrumb: String? = null): Result {
        if (discriminator == null) return matches(sampleData, resolver)

        return getDiscriminatorPattern(discriminatorValue, resolver).realise(
            hasValue = { it, _ -> it.matches(sampleData, resolver) },
            orFailure = { it.failure.breadCrumb(discMisMatchBreadCrumb ?: discriminator.property) },
            orException = { it.toHasFailure().failure.breadCrumb(discMisMatchBreadCrumb ?: discriminator.property) }
        )
    }

    fun generateValue(resolver: Resolver, discriminatorValue: String = ""): Value {
        if (this.isScalarBasedPattern()) {
            return this.pattern.filterNot { it is NullPattern }.let { discriminator?.updatePatternsWithDiscriminator(pattern, resolver)?.listFold()?.value ?: pattern }.first { it is ScalarType }
                .generate(resolver)
        }

        val updatedPatterns =
            if(discriminator != null)
                discriminator.updatePatternsWithDiscriminator(pattern, resolver).listFold().value
            else
                pattern

        val chosenByDiscriminator = getDiscriminatorBasedPattern(updatedPatterns, discriminatorValue, resolver)
        if(chosenByDiscriminator != null)
            return generate(resolver, chosenByDiscriminator)

        data class GenerationResult(val value: Value? = null, val exception: Throwable? = null) {
            val isCycle = exception is ContractException && exception.isCycle
        }

        val generationResults = updatedPatterns.asSequence().map { chosenPattern ->
            try {
                GenerationResult(value = generate(resolver, chosenPattern))
            } catch (e: Throwable) {
                GenerationResult(exception = e)
            }
        }

        val successfulGeneration = generationResults.map { it.value }.filterNotNull().firstOrNull()

        if(successfulGeneration != null)
            return successfulGeneration

        val cycle = generationResults.filter { it.isCycle }.map { it.exception }.firstOrNull()
        if(cycle != null)
            throw cycle

        throw generationResults.firstOrNull { it.exception != null }?.exception ?: ContractException("Could not generate value")
    }

    private fun generate(
        resolver: Resolver,
        chosenPattern: Pattern
    ): Value {
        val isNullable = pattern.any { it is NullPattern }
        return resolver.withCyclePrevention(chosenPattern, isNullable) { cyclePreventedResolver ->
            when (key) {
                null -> chosenPattern.generate(cyclePreventedResolver)
                else -> cyclePreventedResolver.generate(key, chosenPattern)
            }
        } ?: NullValue // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
    }

    fun isScalarBasedPattern(): Boolean {
        return pattern.size == 2 &&
                pattern.any { it is NullPattern} &&
                pattern.filterNot { it is NullPattern }.filter { it is ScalarType }.size == 1
    }

    private fun getDiscriminatorBasedPattern(updatedPatterns: List<Pattern>, discriminatorValue: String, resolver: Resolver): JSONObjectPattern? {
        return updatedPatterns.firstNotNullOfOrNull {
            when (it) {
                is AnyPattern -> it.getDiscriminatorBasedPattern(
                    it.discriminator?.updatePatternsWithDiscriminator(it.pattern, resolver)?.listFold()?.value ?: it.pattern,
                    discriminatorValue = discriminatorValue, resolver = resolver
                )
                is JSONObjectPattern -> {
                    val discriminatorKey = discriminator?.property ?: return@firstNotNullOfOrNull null
                    val keyPattern = it.patternForKey(discriminatorKey) ?: return@firstNotNullOfOrNull null
                    it.takeIf {
                        keyPattern is ExactValuePattern && keyPattern.discriminator && keyPattern.pattern.toStringLiteral() == discriminatorValue
                    }
                }
                else -> null
            }
        }
    }

    private fun newTypesOrExceptionIfNone(patternResults: Sequence<Pair<Sequence<ReturnValue<Pattern>>?, Throwable?>>, message: String): Sequence<ReturnValue<Pattern>> {
        val newPatterns: Sequence<ReturnValue<Pattern>> = patternResults.mapNotNull { it.first }.flatten()

        if (!newPatterns.any() && pattern.isNotEmpty()) {
            val exceptions = patternResults.mapNotNull { it.second }.map {
                when (it) {
                    is ContractException -> it
                    else -> ContractException(exceptionCause = it)
                }
            }

            val failures = exceptions.map { it.failure() }

            val failure = Failure.fromFailures(failures.toList())

            throw ContractException(failure.toFailureReport(message))
        }
        return newPatterns
    }

    private fun distinctableValueOnlyForScalars(it: Pattern): Any {
        if (it is ScalarType || it is ExactValuePattern)
            return it

        return randomString(10)
    }

    private fun allValuesAreScalar() = pattern.all { it is ExactValuePattern && it.pattern is ScalarValue }

    private fun hasNoAmbiguousPatterns(): Boolean {
        return this.pattern.count { it !is NullPattern } == 1
    }

    private fun addTypeInfoBreadCrumbs(matchResults: List<AnyPatternMatch>): List<Failure> {
        if(this.hasNoAmbiguousPatterns()) {
            return matchResults.map { it.result as Failure }
        }

        val failuresWithUpdatedBreadcrumbs = matchResults.map {
            Pair(it.pattern, it.result as Failure)
        }.mapIndexed { index, (pattern, failure) ->
            val ordinal = index + 1

            pattern.typeAlias?.let {
                if (it.isBlank() || it == "()")
                    failure.breadCrumb("(~~~object $ordinal)")
                else
                    failure.breadCrumb("(~~~${withoutPatternDelimiters(it)} object)")
            } ?: failure
        }
        return failuresWithUpdatedBreadcrumbs
    }

    private fun getResult(failures: List<Failure>): List<Failure> = when {
        isNullablePattern() -> {
            val index = pattern.indexOfFirst { !isEmpty(it) }
            listOf(failures[index])
        }
        else -> failures
    }

    private fun isNullablePattern() = pattern.size == 2 && pattern.any { isEmpty(it) }

    private fun isEmpty(it: Pattern) = it.typeAlias == "(empty)" || it is NullPattern
}

private fun failedToFindAny(expected: String, actual: Value?, results: List<Failure>, mismatchMessages: MismatchMessages): Failure =
    when (results.size) {
        1 -> results[0]
        else -> {
            mismatchResult(expected, actual, mismatchMessages)
        }
    }
