package io.specmatic.core.pattern

import io.ktor.http.*
import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.discriminator.DiscriminatorBasedItem
import io.specmatic.core.discriminator.DiscriminatorMetadata
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.EarlyResult
import io.specmatic.core.utilities.firstSuccessOrFailures
import io.specmatic.core.utilities.getOrElse
import io.specmatic.core.value.*

fun List<Pattern>.extractCombinedExtensions(): Map<String, Any> {
    return this.flatMap {
        if (it is PossibleJsonObjectPatternContainer) it.extensions.entries
        else emptyList()
    }.associate { it.toPair() }
}

data class AnyPattern(
    override val pattern: List<Pattern>,
    val key: String? = null,
    override val typeAlias: String? = null,
    override val example: String? = null,
    override val discriminator: Discriminator? = null,
    override val extensions: Map<String, Any> = pattern.extractCombinedExtensions()
) : Pattern,
    HasDefaultExample,
    PossibleJsonObjectPatternContainer,
    SubSchemaCompositePattern {
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
    ), pattern.extractCombinedExtensions())

    data class AnyPatternMatch(val pattern: Pattern, val result: Result)

    private fun extractDiscriminatorValue(value: Value): String? {
        return if (discriminator != null && value is JSONObjectValue && discriminator.property in value.jsonObject) {
            value.jsonObject.getValue(discriminator.property).toStringLiteral()
        } else null
    }

    private fun selectPattern(
        value: Value,
        resolver: Resolver,
        updatedPatterns: List<Pattern> = getUpdatedPattern(resolver),
    ): Pattern {
        val discriminatorValue = extractDiscriminatorValue(value)
        if (discriminatorValue != null) {
            val discriminatorBasedPattern = getDiscriminatorPattern(discriminatorValue, resolver)
            if (discriminatorBasedPattern is HasValue) {
                return discriminatorBasedPattern.value
            }
        }

        val patternMatches = updatedPatterns.sortedBy(::isEmpty).map { pattern ->
            AnyPatternMatch(pattern, pattern.matches(value, resolver))
        }
        val bestMatch = patternMatches.minBy { (it.result as? Failure)?.failureCount() ?: 0 }
        return bestMatch.pattern
    }

    override fun fixValue(
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
        if (resolver.matchesPattern(this, value).isSuccess()) return value

        val updatedResolver = resolver.updateLookupPath(this.typeAlias)

        val discriminatorValue = extractDiscriminatorValue(value)
        if (discriminatorValue != null) {
            val discBasedFixedValue = fixValue(
                value = value,
                resolver = resolver,
                discriminatorValue = discriminatorValue,
                onValidDiscValue = { generateValue(updatedResolver, discriminatorValue) },
                onInvalidDiscValue = { null }
            )
            if (discBasedFixedValue != null) return discBasedFixedValue
        }

        val selectedPattern = selectPattern(value, resolver)
        return selectedPattern.fixValue(value, updatedResolver)
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

        val pattern = this.pattern.first { !isEmpty(it) }
        if (pattern is JSONObjectPattern) return pattern
        if (pattern is PossibleJsonObjectPatternContainer) return pattern.jsonObjectPattern(resolver)
        return null
    }

    override fun ensureAdditionalProperties(resolver: Resolver): AnyPattern {
        return this.copy(pattern = this.pattern.map { pattern ->
            if (pattern !is PossibleJsonObjectPatternContainer) return@map pattern
            pattern.ensureAdditionalProperties(resolver)
        })
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

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        val patternToConsider = when (val resolvedPattern = resolveToPattern(value, resolver, this)) {
            is ReturnFailure -> return resolvedPattern.cast()
            else -> resolvedPattern.value
        }

        if (isPatternToken(value) && patternToConsider == this) return HasValue(resolver.generate(this))
        return evaluateWithUpdatedResolver(resolver) { pattern, updatedResolver ->
            pattern.fillInTheBlanks(value, updatedResolver, removeExtraKeys)
        }
    }

    override fun resolveSubstitutions(substitution: Substitution, value: Value, resolver: Resolver, key: String?): ReturnValue<Value> {
        val updatedPatterns = getUpdatedPattern(resolver)
        val patternsToEvaluate = selectPatternsForSubstitution(substitution, value, resolver, updatedPatterns)

        return evaluateWithUpdatedResolver(resolver, updatedPatterns, patternsToEvaluate) { pattern, updatedResolver ->
            pattern.resolveSubstitutions(substitution, value, updatedResolver, key)
        }
    }

    private fun selectPatternsForSubstitution(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        updatedPatterns: List<Pattern>,
    ): List<Pattern> {
        if (updatedPatterns.isEmpty()) return emptyList()

        val valueForSelection = (substitution.substitute(value) as? HasValue)?.value ?: value
        return listOf(selectPattern(valueForSelection, resolver, updatedPatterns))
    }

    private inline fun evaluateWithUpdatedResolver(
        resolver: Resolver,
        updatedPatterns: List<Pattern> = getUpdatedPattern(resolver),
        patternsToEvaluate: List<Pattern> = updatedPatterns,
        crossinline evaluate: (Pattern, Resolver) -> ReturnValue<Value>
    ): ReturnValue<Value> {
        val newPatterns = updatedPatterns
            .filter { it.typeAlias != null && it !is DeferredPattern }
            .associateBy { it.typeAlias.orEmpty() }

        val updatedResolver = resolver
            .copy(newPatterns = resolver.newPatterns.plus(newPatterns))
            .updateLookupPath(this.typeAlias)

        val result = patternsToEvaluate.firstSuccessOrFailures(
            evaluate = { evaluate(it, updatedResolver) },
            isSuccess = { it is HasValue },
            toFailure = { it as ReturnFailure }
        )

        return result.getOrElse { failures ->
            HasFailure(Failure.fromFailures(failures.map { it.toFailure() }))
        }
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        val initialValue: ReturnValue<Map<String, Pattern>> = HasValue(emptyMap())

        return pattern.fold(initialValue) { acc, pattern ->
            val templateTypes = pattern.getTemplateTypes("", value, resolver)
            acc.assimilate(templateTypes) { data, additional -> data + additional }
        }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (discriminator != null) {
            return discriminator.matches(sampleData, pattern, key, resolver)
        }

        val earlyResult  = pattern.firstSuccessOrFailures(
            evaluate = { pattern ->
                val result = resolver.matchesPattern(pattern, sampleData ?: EmptyString)
                AnyPatternMatch(pattern, result)
            },
            isSuccess = { it.result.isSuccess() },
            toFailure = { it },
        )

        val matchResults = when (earlyResult) {
            is EarlyResult.FirstSuccess -> return earlyResult.value.result
            is EarlyResult.Failures -> earlyResult.failures
        }

        val failures = matchResults.map { it.result }.filterIsInstance<Failure>()
        if (failures.any { it.reasonIs { it.objectMatchOccurred } }) {
            val failureMatchResults = matchResults.filter {
                it.result is Failure && it.result.reasonIs { it.objectMatchOccurred }
            }

            val objectTypeMatchedButHadSomeOtherMismatch = addTypeInfoBreadCrumbs(failureMatchResults)

            return Failure.fromFailures(objectTypeMatchedButHadSomeOtherMismatch).removeReasonsFromCauses()
        }

        val resolvedPatterns = pattern.map { resolvedHop(it, resolver) }

        if(resolvedPatterns.any(::isEmpty) || resolvedPatterns.all { it is ExactValuePattern })
            return when {
                sampleData is ScalarValue && anyPatternIsEnum() -> {
                    FailedToFindAnyUsingTypeValueDescription(sampleData)
                }
                else -> {
                    FailedToFindAnyUsingValue(sampleData)
                }
            }.failedToFindAny(typeName, getResult(matchResults.map { it.result as Failure }), resolver.mismatchMessages)

        val failuresWithUpdatedBreadcrumbs = addTypeInfoBreadCrumbs(matchResults)
        return Result.fromFailures(failures = failuresWithUpdatedBreadcrumbs)
    }

    private fun anyPatternIsEnum(): Boolean {
        return pattern.all { it is ExactValuePattern && it.pattern is ScalarValue }
    }

    @Suppress("MemberVisibilityCanBePrivate") // Being used in openapi
    fun getUpdatedPattern(resolver: Resolver): List<Pattern> {
        return discriminator?.updatePatternsWithDiscriminator(pattern, resolver)?.listFold()?.value ?: pattern
    }

    override fun generate(resolver: Resolver): Value {
        return resolver.resolveExample(example, pattern)
            ?: generateValue(resolver)
    }

    private fun rowForPattern(row: Row, pattern: Pattern, resolver: Resolver, discriminator: Discriminator? = null): Row {
        val example = row.requestBodyJSONExample?.jsonObject as? Value ?: return row
        return try {
            val matches = discriminator?.let { matchesDiscriminator(example, pattern, it, resolver) }
                ?: pattern.matches(sampleData = example, resolver = resolver).isSuccess()
            if (matches) row else Row()
        } catch (e: Throwable) {
            val matchStrategy = if (discriminator != null) "discriminator" else "pattern"
            val patternName = pattern.typeAlias?.let(::withoutPatternDelimiters) ?: "${pattern.typeName} (${pattern.javaClass.name})"
            logger.debug(e, "Error while matching example row '${row.name}' against pattern '$patternName' using $matchStrategy strategy, falling back to empty example row")
            Row()
        }
    }

    private fun matchesDiscriminator(example: Value, pattern: Pattern, discriminator: Discriminator, resolver: Resolver): Boolean {
        val exampleObject = example as? JSONObjectValue ?: return false
        val discriminatorValue = exampleObject.jsonObject[discriminator.property] ?: return false
        return getDiscriminatorBasedPattern(listOf(pattern), discriminatorValue.toStringLiteral(), resolver) != null
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val updatedPatterns = discriminator?.let {
            it.updatePatternsWithDiscriminator(pattern, resolver).let { updatedPatterns ->
                if(updatedPatterns.any { it !is HasValue<Pattern> }) {
                    val failures = updatedPatterns.mapNotNull { pattern ->
                        when (pattern) {
                            is HasValue -> null
                            is HasFailure -> pattern.failure
                            is HasException -> pattern.toFailure()
                        }
                    }
                    return sequenceOf(HasFailure(Failure.fromFailures(failures)))
                }

                updatedPatterns.listFold().value
            }
        } ?: pattern

        resolver.resolveExample(example, updatedPatterns)?.let {
            return sequenceOf(HasValue(ExactValuePattern(it)))
        }

        val isNullable = updatedPatterns.any(::isEmpty)
        val patternResults: Sequence<Pair<Sequence<ReturnValue<Pattern>>?, Throwable?>> = updatedPatterns.asSequence().sortedBy(::isEmpty).mapNotNull { innerPattern ->
            try {
                resolver.withCyclePrevention(innerPattern, isNullable) { cyclePreventedResolver ->
                    val rowForInnerPattern = rowForPattern(row, innerPattern, cyclePreventedResolver, discriminator)
                    val rowWithoutDiscriminator = discriminator?.removeKeyFromRow(rowForInnerPattern) ?: rowForInnerPattern
                    Pair(innerPattern.newBasedOn(rowWithoutDiscriminator, cyclePreventedResolver), null)
                }
            } catch (e: Throwable) {
                Pair(null, e)
            }
        }

        return newTypesOrExceptionIfNone(patternResults, "Could not generate new tests")
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        val isNullable = isNullablePattern()
        return pattern.asSequence().flatMap { innerPattern ->
            resolver.withCyclePrevention(innerPattern, isNullable) { cyclePreventedResolver ->
                innerPattern.newBasedOn(cyclePreventedResolver)
            }?: emptySequence()  // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        val nullable = isNullablePattern()
        val negativeTypeResults = getUpdatedPattern(resolver).filterNot(::isEmpty).asSequence().map {
            try {
                val rowForInnerPattern = rowForPattern(row, it, resolver, discriminator)
                val patterns: Sequence<ReturnValue<Pattern>> = it.negativeBasedOn(rowForInnerPattern, resolver, config)
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
                patterns.filterValueIsNot(::isEmpty)
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
        val nonNullTypesFirst = resolvedTypes.sortedBy(::isEmpty)

        val failures = mutableListOf<Failure>()
        for (pattern in nonNullTypesFirst) {
            try {
                return pattern.parse(value, resolver)
            } catch (e: Throwable) {
                failures.add(e.toFailure())
            }
        }

        throw ContractException(Failure.fromFailures(failures).toFailureReport())
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
            patternMismatchResult(this, otherPattern, thisResolver.mismatchMessages)
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
                val concreteTypeName = withoutPatternDelimiters(pattern.filterNot(::isEmpty).first().typeName)
                "($concreteTypeName?)"
            } else
                "(${pattern.joinToString(" or ") { inner -> withoutPatternDelimiters(inner.typeName).let { if(it == "null") "\"null\"" else it}  }})"
        }

    override fun toNullable(defaultValue: String?): AnyPattern {
        if (isNullablePattern()) return this
        return this.copy(pattern = pattern.plus(NullPattern), example = example ?: defaultValue)
    }

    override fun isDiscriminatorPresent() = discriminator?.isNotEmpty() == true

    fun hasMultipleDiscriminatorValues() = discriminator?.hasMultipleValues() == true

    override fun generateForEveryDiscriminatorValue(resolver: Resolver): List<DiscriminatorBasedItem<Value>> {
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

    override fun matchesValue(sampleData: Value?, resolver: Resolver, discriminatorValue: String, discMisMatchBreadCrumb: String?): Result {
        if (discriminator == null) return matches(sampleData, resolver)

        return getDiscriminatorPattern(discriminatorValue, resolver).realise(
            hasValue = { it, _ -> it.matches(sampleData, resolver) },
            orFailure = { it.failure.breadCrumb(discMisMatchBreadCrumb ?: discriminator.property) },
            orException = { it.toHasFailure().failure.breadCrumb(discMisMatchBreadCrumb ?: discriminator.property) }
        )
    }

    override fun generateValue(resolver: Resolver, discriminatorValue: String): Value {
        data class GenerationResult(val value: Value? = null, val exception: Throwable? = null) {
            val isCycle = exception is ContractException && exception.isCycle
        }

        val updatedPatterns = getUpdatedPattern(resolver)
        val chosenByDiscriminator = getDiscriminatorBasedPattern(updatedPatterns, discriminatorValue, resolver)
        if (chosenByDiscriminator != null) return generate(resolver, chosenByDiscriminator)

        val generationResults = updatedPatterns.sortedBy(::isEmpty).asSequence().map { chosenPattern ->
            try {
                GenerationResult(value = generate(resolver, chosenPattern))
            } catch (e: Throwable) {
                GenerationResult(exception = e)
            }
        }

        val successfulGeneration = generationResults.firstNotNullOfOrNull { it.value }
        if(successfulGeneration != null) return successfulGeneration

        val cycle = generationResults.firstOrNull { it.isCycle }?.exception
        if(cycle != null) throw cycle

        throw generationResults.firstOrNull { it.exception != null }?.exception ?: ContractException("Could not generate value")
    }

    private fun generate(
        resolver: Resolver,
        chosenPattern: Pattern
    ): Value {
        val isNullable = isNullablePattern()
        return resolver.withCyclePrevention(chosenPattern, isNullable) { cyclePreventedResolver ->
            when (key) {
                null -> chosenPattern.generate(cyclePreventedResolver)
                else -> cyclePreventedResolver.generate(chosenPattern)
            }
        } ?: NullValue // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
    }

    @Suppress("unused") // Being used in openapi
    fun isNullableScalarPattern(): Boolean {
        return pattern.all { it is ScalarType || (it is ExactValuePattern && it.pattern is ScalarValue) } && isNullablePattern()
    }

    override fun getDiscriminatorBasedPattern(updatedPatterns: List<Pattern>, discriminatorValue: String, resolver: Resolver): JSONObjectPattern? {
        return updatedPatterns.firstNotNullOfOrNull {
            when (it) {
                is SubSchemaCompositePattern -> it.getDiscriminatorBasedPattern(
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

    override fun calculatePath(value: Value, resolver: Resolver): Set<String> {
        // Find which pattern in the list matches the given value
        val matchingPatternIndex = pattern.indexOfFirst { pattern ->
            val resolvedPattern = resolvedHop(pattern, resolver)
            resolvedPattern.matches(value, resolver) is Result.Success
        }
        
        if (matchingPatternIndex == -1) {
            return emptySet()
        }
        
        val matchingPattern = resolvedHop(pattern[matchingPatternIndex], resolver)
        val originalPattern = pattern[matchingPatternIndex]
        
        // Handle DeferredPattern specially to preserve typeAlias information
        val patternTypeAlias = when (originalPattern) {
            is DeferredPattern -> {
                // For DeferredPattern, extract typeAlias and remove parentheses using withoutPatternDelimiters
                withoutPatternDelimiters(originalPattern.pattern)
            }
            else -> originalPattern.typeAlias?.let { withoutPatternDelimiters(it) }
        }
        
        // If the resolved pattern is a JSONObjectPattern with nested AnyPatterns, 
        // we need to recurse to get the nested paths
        if (matchingPattern is JSONObjectPattern) {
            val nestedPaths = matchingPattern.calculatePath(value, resolver)
            if (nestedPaths.isNotEmpty()) {
                // The nested paths already contain the proper formatting, just return them
                return nestedPaths
            } else {
                // JSONObjectPattern but no nested AnyPatterns found
                if (patternTypeAlias != null && patternTypeAlias.isNotBlank()) {
                    return setOf("{$patternTypeAlias}")
                }
            }
        }
        
        // If the matching pattern has a typeAlias, use it
        if (patternTypeAlias != null && patternTypeAlias.isNotBlank()) {
            return setOf("{$patternTypeAlias}")
        }
        
        // If no typeAlias and it's a simple scalar pattern, return the scalar type name
        if (matchingPattern is StringPattern || matchingPattern is NumberPattern || matchingPattern is BooleanPattern) {
            val scalarTypeName = when (matchingPattern) {
                is StringPattern -> "string"
                is NumberPattern -> "number"
                is BooleanPattern -> "boolean"
                else -> null
            }
            return if (scalarTypeName != null) setOf(scalarTypeName) else emptySet()
        }
        
        // If no typeAlias but it's a complex pattern, return the index in the format {[index]}
        return setOf("{[$matchingPatternIndex]}")
    }

    override fun patternFrom(value: Value, resolver: Resolver, parseValueToType: (Value) -> Pattern): Pattern {
        val selectedPattern = selectPattern(value, resolver)
        return selectedPattern.patternFrom(value, resolver, parseValueToType)
    }

    private fun allValuesAreScalar() = pattern.all { it is ExactValuePattern && it.pattern is ScalarValue }

    private fun hasNoAmbiguousPatterns(): Boolean {
        return this.pattern.count { !isEmpty(it) } == 1
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
            failures.getOrNull(index)?.let(::listOf) ?: failures
        }
        else -> failures
    }

    private fun isNullablePattern() = pattern.any(::isEmpty)

    private fun isEmpty(it: Pattern) = it.typeAlias == "(empty)" || it is NullPattern || (it is ExactValuePattern && it.pattern is NullValue)
}


private interface FailedToFindAny {
    fun failedToFindAny(expected: String, results: List<Failure>, mismatchMessages: MismatchMessages): Failure
}

private class FailedToFindAnyUsingTypeValueDescription <V> (val actual: V) : FailedToFindAny where V : Value, V : ScalarValue {
    override fun failedToFindAny(
        expected: String,
        results: List<Failure>,
        mismatchMessages: MismatchMessages
    ): Failure {
        val displayableValueOfActual = actual.displayableValue()

        val description: String = when(actual) {
            is StringValue -> displayableValueOfActual
            else -> "$displayableValueOfActual (${actual.type().typeName})"
        }

        return valueMismatchResult(expected, description, mismatchMessages)
    }

}

private class FailedToFindAnyUsingValue(val actual: Value?) : FailedToFindAny {
    override fun failedToFindAny(
        expected: String,
        results: List<Failure>,
        mismatchMessages: MismatchMessages
    ): Failure {
        return when (results.size) {
            1 -> results[0]
            else -> {
                valueMismatchResult(expected, actual, mismatchMessages)
            }
        }
    }
}
