package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.*

const val LIST_BREAD_CRUMB = "[]"

data class ListPattern(
    override val pattern: Pattern,
    override val typeAlias: String? = null,
    override val example: List<String?>? = null,
    override val extensions: Map<String, Any>  = emptyMap()
) : Pattern, SequenceType, HasDefaultExample, PossibleJsonObjectPatternContainer {
    override val memberList: MemberList
        get() = MemberList(emptyList(), pattern)

    override fun fixValue(value: Value, resolver: Resolver): Value {
        if (resolver.matchesPattern(null, this, value).isSuccess()) return value
        if (value !is JSONArrayValue || (value.list.isEmpty() && resolver.allPatternsAreMandatory && !resolver.hasPartialKeyCheck())) {
            return pattern.listOf(0.until(randomNumber(3)).mapIndexed { index, _ ->
                attempt(breadCrumb = "[$index (random)]") { pattern.fixValue(NullValue, resolver) }
            }, resolver)
        }

        val updatedResolver = resolver.addPatternAsSeen(this)
        return JSONArrayValue(value.list.map { pattern.fixValue(it, updatedResolver) })
    }

    override fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        if (value !is JSONArrayValue) return value

        return JSONArrayValue(value.list.map {
            pattern.eliminateOptionalKey(it, resolver)
        })
    }

    override fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String?): Pattern {
        if(concretePattern !is JSONArrayPattern)
            return concretePattern

        return concretePattern.copy(
            typeAlias = typeAlias ?: this.typeAlias,
            pattern = concretePattern.pattern.map { concreteItemPattern ->
                pattern.addTypeAliasesToConcretePattern(concreteItemPattern, resolver)
            }
        )
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        val patternToConsider = when (val resolvedPattern = resolveToPattern(value, resolver, this)) {
            is ReturnFailure -> return resolvedPattern.cast()
            else -> (resolvedPattern.value as? ListPattern) ?: return when(resolver.isNegative) {
                true -> fillInIfPatternToken(value, resolvedPattern.value, resolver)
                else -> HasFailure("Pattern is not a list pattern")
            }
        }.pattern

        val fallbackAnyValueList = List(randomNumber(3)) { StringValue("(anyvalue)") }
        val valueToConsider = when {
            value is JSONArrayValue -> value.list.takeUnless { it.isEmpty() && resolver.allPatternsAreMandatory } ?: fallbackAnyValueList
            isPatternToken(value) -> fallbackAnyValueList
            resolver.isNegative -> return HasValue(value)
            else -> return HasFailure("Cannot generate a list from type ${value.displayableType()}")
        }

        return valueToConsider.mapIndexed { index, item ->
            val updatedResolver = resolver.updateLookupPath(this, this.pattern)
            patternToConsider.fillInTheBlanks(item, updatedResolver, removeExtraKeys).breadCrumb("[$index]")
        }.listFold().ifValue(::JSONArrayValue)
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        if(value !is JSONArrayValue)
            return HasFailure(Result.Failure("Cannot resolve substitutions, expected list but got ${value.displayableType()}"))

        val updatedList = value.list.mapIndexed { index, listItem ->
            pattern.resolveSubstitutions(substitution, listItem, resolver).breadCrumb("[$index]")
        }.listFoldException()

        return updatedList.ifValue { value.copy(list = it) }
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        if(value !is JSONArrayValue)
            return HasFailure(Result.Failure("Cannot resolve data substitutions, expected list but got ${value.displayableType()}"))

        val initialValue: ReturnValue<Map<String, Pattern>> = HasValue(emptyMap())
        return value.list.fold(initialValue) { acc, valuePattern ->
            val patterns = pattern.getTemplateTypes("", valuePattern, resolver)

            acc.assimilate(patterns) { data, additional -> data + additional }
        }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData !is ListValue)
            return when {
                resolvedHop(pattern, resolver) is XMLPattern -> mismatchResult("xml nodes", sampleData, resolver.mismatchMessages)
                else -> mismatchResult(this, sampleData, resolver.mismatchMessages)
            }

        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        val patternToCheck = this.typeAlias?.let { this } ?: this.pattern
        if (resolverWithEmptyType.allPatternsAreMandatory && !resolverWithEmptyType.hasSeenPattern(patternToCheck) && sampleData.list.isEmpty()) {
            return Result.Failure(message = "List cannot be empty")
        }

        val updatedResolver = resolverWithEmptyType.addPatternAsSeen(this)
        val failures: List<Result.Failure> = sampleData.list.map {
            updatedResolver.matchesPattern(null, pattern, it)
        }.mapIndexed { index, result ->
            ResultWithIndex(index, result)
        }.filter {
            it.result is Result.Failure
        }.map {
            it.result.breadCrumb("[${it.index}]") as Result.Failure
        }

        return if(failures.isEmpty())
            Result.Success()
        else
            Result.Failure.fromFailures(failures)
    }

    override fun generate(resolver: Resolver): Value {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return resolver.resolveExample(example, pattern) ?: dictionaryLookup(resolverWithEmptyType)
    }

    private fun dictionaryLookup(resolver: Resolver): Value {
        return resolver.generateList(this)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return attempt(breadCrumb = LIST_BREAD_CRUMB) {
            resolverWithEmptyType.withCyclePrevention(pattern, true) { cyclePreventedResolver ->
                val patterns = pattern.newBasedOn(row.stepDownIntoList(), cyclePreventedResolver)
                try {
                    patterns.firstOrNull()?.value
                    patterns.map {
                        it.ifValue { ListPattern(it) }
                    }
                } catch(e: ContractException) {
                    if(e.isCycle)
                        null
                    else
                        throw e
                }
            } ?: sequenceOf(HasValue(ExactValuePattern(JSONArrayValue(emptyList()))))
        }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return attempt(breadCrumb = LIST_BREAD_CRUMB) {
            resolverWithEmptyType.withCyclePrevention(pattern) { cyclePreventedResolver ->
                pattern.newBasedOn(cyclePreventedResolver).map { ListPattern(it) }
            }
        }
    }

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> {
        return attempt(breadCrumb = LIST_BREAD_CRUMB) {
            pattern.negativeBasedOn(row.stepDownIntoList(), resolver, config)
                .map { negativePatternValue ->
                    negativePatternValue.ifValue { pattern ->
                        ListPattern(pattern) as Pattern
                    }.breadCrumb(LIST_BREAD_CRUMB)
                }
        }
    }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONArray(value, resolver.mismatchMessages)

    override fun patternSet(resolver: Resolver): List<Pattern> {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return pattern.patternSet(resolverWithEmptyType)
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val thisResolverWithEmptyType = withEmptyType(pattern, thisResolver)
        val otherResolverWithEmptyType = withEmptyType(pattern, otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolverWithEmptyType, thisResolverWithEmptyType, typeStack)
            is ListPattern -> biggerEncompassesSmaller(pattern, otherPattern.pattern, thisResolverWithEmptyType, otherResolverWithEmptyType, typeStack)
            is SequenceType -> {
                val results = otherPattern.memberList.getEncompassables(otherResolverWithEmptyType).asSequence().mapIndexed { index, otherPatternEntry ->
                    Pair(index, biggerEncompassesSmaller(pattern, otherPatternEntry, thisResolverWithEmptyType, otherResolverWithEmptyType, typeStack))
                }

                results.find { it.second is Result.Failure }?.let { result -> result.second.breadCrumb("[${result.first}]") } ?: Result.Success()
            }
            else -> Result.Failure("Expected array or list type, got ${otherPattern.typeName}")
        }
    }

    override fun encompasses(others: List<Pattern>, thisResolver: Resolver, otherResolver: Resolver, lengthError: String, typeStack: TypeStack): ConsumeResult<Pattern, Pattern> {
        val thisResolverWithEmptyType = withEmptyType(pattern, thisResolver)
        val otherResolverWithEmptyType = withEmptyType(pattern, otherResolver)

        val results = others.asSequence().mapIndexed { index, otherPattern ->
            when (otherPattern) {
                is ExactValuePattern ->
                    otherPattern.fitsWithin(listOf(this.pattern), otherResolverWithEmptyType, thisResolverWithEmptyType, typeStack)
                is SequenceType ->
                    biggerEncompassesSmaller(pattern, resolvedHop(otherPattern, otherResolverWithEmptyType), thisResolverWithEmptyType, otherResolverWithEmptyType, typeStack)
                else -> Result.Failure("Expected array or list type, got ${otherPattern.typeName}")
            }.breadCrumb("[$index]")
        }

        val result = results.find { it is Result.Failure } ?: Result.Success()

        return ConsumeResult(result, emptyList())
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return resolverWithEmptyType.withCyclePrevention(pattern) { pattern.listOf(valueList, it) }
    }

    override val typeName: String = "list of ${pattern.typeName}"

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        if(keys.isEmpty()) return this
        if(pattern is PossibleJsonObjectPatternContainer) {
            return this.copy(pattern = pattern.removeKeysNotPresentIn(keys, resolver))
        }
        return this
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern? {
        return null
    }

    fun calculatePath(value: Value, resolver: Resolver): Set<String> {
        if (value !is JSONArrayValue) return emptySet()
        
        return value.list.flatMapIndexed { index, arrayItem ->
            val resolvedPattern = resolvedHop(pattern, resolver)
            when (resolvedPattern) {
                is AnyPattern -> {
                    // For AnyPattern, get the path and add array index prefix
                    val anyPatternPaths = resolvedPattern.calculatePath(arrayItem, resolver)
                    anyPatternPaths.map { path ->
                        if (path in setOf("string", "number", "boolean")) {
                            "{[$index]}{$path}"
                        } else {
                            "{[$index]}$path"
                        }
                    }
                }
                is JSONObjectPattern -> {
                    // For JSONObjectPattern, recursively get paths and add array index prefix
                    val nestedPaths = resolvedPattern.calculatePath(arrayItem, resolver)
                    nestedPaths.map { nestedPath ->
                        if (nestedPath.startsWith("{")) {
                            "{[$index]}$nestedPath"
                        } else {
                            "{[$index]}.$nestedPath"
                        }
                    }
                }
                else -> emptyList()
            }
        }.toSet()
    }
}

private fun withEmptyType(pattern: Pattern, resolver: Resolver): Resolver {
    val patternSet = pattern.patternSet(resolver)

    val hasXML = patternSet.any { resolvedHop(it, resolver) is XMLPattern }

    val emptyType = if(hasXML) EmptyStringPattern else NullPattern

    return resolver.copy(newPatterns = resolver.newPatterns.plus("(empty)" to emptyType))
}
