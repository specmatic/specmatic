package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.Substitution
import io.specmatic.core.mismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.*

data class AnyOfPattern(
    override val pattern: List<Pattern>,
    val key: String? = null,
    override val typeAlias: String? = null,
    override val example: String? = null,
    override val extensions: Map<String, Any> = pattern.extractCombinedExtensions()
) : Pattern, HasDefaultExample, PossibleJsonObjectPatternContainer {

    data class AnyOfPatternMatch(val pattern: Pattern, val result: Result)

    override fun fixValue(value: Value, resolver: Resolver): Value {
        if (resolver.matchesPattern(null, this, value).isSuccess()) return value

        val patternMatches = pattern.map { pattern ->
            AnyOfPatternMatch(pattern, pattern.matches(value, resolver))
        }

        val matchingPatternNew = patternMatches.minBy { (it.result as? Failure)?.failureCount() ?: 0 }
        val updatedResolver = resolver.updateLookupPath(this.typeAlias)
        return matchingPatternNew.pattern.fixValue(value, updatedResolver)
    }

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        if(keys.isEmpty()) return this

        return this.copy(pattern = this.pattern.map {
            if (it !is PossibleJsonObjectPatternContainer) return@map it
            it.removeKeysNotPresentIn(keys, resolver)
        })
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern? {
        val pattern = this.pattern.first { it !is NullPattern }
        if (pattern is JSONObjectPattern) return pattern
        if (pattern is PossibleJsonObjectPatternContainer) return pattern.jsonObjectPattern(resolver)
        return null
    }

    override fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        val matchingPattern = pattern.find { it.matches(value, resolver) is Result.Success } ?: return value
        return matchingPattern.eliminateOptionalKey(value, resolver)
    }

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

        val updatedResolver = resolver.updateLookupPath(this.typeAlias)

        if (removeExtraKeys && value is JSONObjectValue) {
            val jsonObjectPatterns =
                pattern
                    .map { resolvedHop(it, updatedResolver) }
                    .filterIsInstance<JSONObjectPattern>()

            if (jsonObjectPatterns.isNotEmpty()) {
                val allKeys = jsonObjectPatterns.flatMap { it.pattern.keys.map { key -> withoutOptionality(key) } }.toSet()
                val filteredJsonObject = value.jsonObject.filterKeys { it in allKeys }
                val filteredValue = JSONObjectValue(filteredJsonObject)

                return fillInTheBlanks(filteredValue, updatedResolver, removeExtraKeys)
            }
        }

        return fillInTheBlanks(value, updatedResolver, removeExtraKeys)
    }

    private fun fillInTheBlanks(
        filteredValue: JSONObjectValue,
        updatedResolver: Resolver,
        removeExtraKeys: Boolean,
    ): ReturnValue<Value> {
        val results = pattern.asSequence().map { it.fillInTheBlanks(filteredValue, updatedResolver, removeExtraKeys) }
        val successfulGeneration = results.firstOrNull { it is HasValue }
        if (successfulGeneration != null) return successfulGeneration

        val resultList = results.toList()
        val failures = resultList.filterIsInstance<ReturnFailure>().map { it.toFailure() }
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

        if (options.any { it is HasValue }) {
            val combinedValue = options.filterIsInstance<HasValue<Value>>().map { it.value }.reduce { val1, val2 ->
                if (val1 is JSONObjectValue && val2 is JSONObjectValue) {
                    JSONObjectValue(val1.jsonObject + val2.jsonObject)
                } else {
                    val2
                }
            }

            return HasValue(combinedValue)
        }

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
        if (pattern.isEmpty()) {
            return Failure("No patterns available to match against")
        }
        
        val resolverWithIgnoreUnexpectedKeys = resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        val matchResults: List<AnyOfPatternMatch> =
            pattern.map {
                AnyOfPatternMatch(it, resolverWithIgnoreUnexpectedKeys.matchesPattern(key, it, sampleData ?: EmptyString))
            }

        val matchResult = matchResults.find { it.result is Result.Success }

        if(matchResult != null)
            return matchResult.result

        return Result.fromFailures(matchResults.map { it.result }.filterIsInstance<Failure>())
    }

    override fun generate(resolver: Resolver): Value {
        return resolver.resolveExample(example, pattern)
            ?: generateValue(resolver)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        resolver.resolveExample(example, pattern)?.let {
            return sequenceOf(HasValue(ExactValuePattern(it)))
        }

        val patternResults: Sequence<Pair<Sequence<ReturnValue<Pattern>>?, Throwable?>> =
            pattern.asSequence().map { innerPattern ->
                try {
                    val patterns =
                        resolver.withCyclePrevention(innerPattern, false) { cyclePreventedResolver ->
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
        return pattern.asSequence().flatMap { innerPattern ->
            resolver.withCyclePrevention(innerPattern, false) { cyclePreventedResolver ->
                innerPattern.newBasedOn(cyclePreventedResolver)
            }?: emptySequence()  // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
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
        )

        return negativeTypes.distinctBy {
            it.withDefault(randomString(10)) {
                distinctableValueOnlyForScalars(it)
            }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        val resolvedTypes = pattern.map { resolvedHop(it, resolver) }

        return resolvedTypes.asSequence().map {
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
            throw ContractException("AnyOfPattern doesn't have any types, so can't infer which type of list to wrap the given value in")

        return pattern.first().listOf(valueList, resolver)
    }

    override val typeName: String
        get() {
            return "(anyOf ${pattern.joinToString(" ") { inner -> withoutPatternDelimiters(inner.typeName).let { if(it == "null") "\"null\"" else it}  }})"
        }

    override fun toNullable(defaultValue: String?): Pattern {
        return this
    }

    fun generateValue(resolver: Resolver): Value {
        data class GenerationResult(val value: Value? = null, val exception: Throwable? = null) {
            val isCycle = exception is ContractException && exception.isCycle
        }

        val generationResults = pattern.asSequence().map { chosenPattern ->
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
        return resolver.withCyclePrevention(chosenPattern, false) { cyclePreventedResolver ->
            when (key) {
                null -> chosenPattern.generate(cyclePreventedResolver)
                else -> cyclePreventedResolver.generate(key, chosenPattern)
            }
        } ?: NullValue // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
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
}