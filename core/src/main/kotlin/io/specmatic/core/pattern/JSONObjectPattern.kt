package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.mapZip
import io.specmatic.core.utilities.stringToPatternMap
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.*
import java.util.Optional

fun toJSONObjectPattern(jsonContent: String, typeAlias: String?): JSONObjectPattern =
    toJSONObjectPattern(stringToPatternMap(jsonContent), typeAlias)

fun toJSONObjectPattern(
    map: Map<String, Pattern>,
    typeAlias: String? = null,
    extensions: Map<String, Any> = emptyMap()
): JSONObjectPattern {
    val missingKeyStrategy: UnexpectedKeyCheck = when ("...") {
        in map -> IgnoreUnexpectedKeys
        else -> ValidateUnexpectedKeys
    }

    return JSONObjectPattern(
        pattern = map.minus("..."),
        unexpectedKeyCheck = missingKeyStrategy,
        typeAlias = typeAlias,
        extensions = extensions
    )
}

sealed interface AdditionalProperties {
    fun updatePatternMap(patternMap: Map<String, Pattern>, valueMap: Map<String, Value>): Map<String, Pattern>
    fun encompasses(other: AdditionalProperties, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result
    val description: String

    data object NoAdditionalProperties : AdditionalProperties {
        override fun updatePatternMap(patternMap: Map<String, Pattern>, valueMap: Map<String, Value>): Map<String, Pattern> {
            return patternMap
        }

        override fun encompasses(other: AdditionalProperties, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
            return if (other is NoAdditionalProperties) Result.Success()
            else Result.Failure("Expected $description, but updated schema allowed ${other.description}")
        }

        override val description: String
            get() = "no additional properties"
    }

    data object FreeForm : AdditionalProperties {
        override fun updatePatternMap(patternMap: Map<String, Pattern>, valueMap: Map<String, Value>): Map<String, Pattern> {
            val extraKeys = valueMap.excludingPatternKeys(patternMap)
            return patternMap + extraKeys.associateWith { AnyValuePattern }
        }

        override fun encompasses(other: AdditionalProperties, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
            return Result.Success()
        }

        override val description: String
            get() = "a free form object"
    }

    data class PatternConstrained(val pattern: Pattern): AdditionalProperties {
        override fun updatePatternMap(patternMap: Map<String, Pattern>, valueMap: Map<String, Value>): Map<String, Pattern> {
            val extraKeys = valueMap.excludingPatternKeys(patternMap)
            return patternMap + extraKeys.associateWith { pattern }
        }

        override fun encompasses(other: AdditionalProperties, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
            return when(other) {
                is NoAdditionalProperties -> Result.Success()
                is FreeForm -> Result.Failure("value constrained additional properties does not encompass free form")
                is PatternConstrained -> this.pattern.encompasses(other.pattern, thisResolver, otherResolver, typeStack)
            }
        }

        override val description: String
            get() = "additional properties constrained by ${pattern.typeAlias?.let { withoutPatternDelimiters(it) } ?: "a schema"}"
    }

    fun Map<String, Value>.excludingPatternKeys(pattern: Map<String, Pattern>): Set<String> {
        val patternKeys = pattern.keys.map(::withoutOptionality).toSet()
        return keys.minus(patternKeys)
    }
}

data class JSONObjectPattern(
    override val pattern: Map<String, Pattern> = emptyMap(),
    private val unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys,
    override val typeAlias: String? = null,
    val minProperties: Int? = null,
    val maxProperties: Int? = null,
    val additionalProperties: AdditionalProperties = AdditionalProperties.NoAdditionalProperties,
    override val extensions: Map<String, Any> = emptyMap()
) : Pattern, PossibleJsonObjectPatternContainer {

    override fun fixValue(value: Value, resolver: Resolver): Value {
        if (resolver.matchesPattern(null, this, value).isSuccess()) return value
        val valueMap = (value as? JSONObjectValue)?.jsonObject.orEmpty()
        val adjustedPattern = additionalProperties.updatePatternMap(pattern, valueMap)

        return JSONObjectValue(
            fix(jsonPatternMap = adjustedPattern, jsonValueMap = valueMap, resolver = resolver, jsonPattern = this)
        )
    }

    override fun eliminateOptionalKey(value: Value, resolver: Resolver): Value {
        if (value !is JSONObjectValue) return value

        val mandatoryObjectPatternMap  = this.pattern.filterKeys { !isOptional(it) }
        val mandatoryObjectMap = value.jsonObject.mapNotNull { (key, actualValue) ->
            val patternForKey = mandatoryObjectPatternMap[key] ?: return@mapNotNull null
            key to patternForKey.eliminateOptionalKey(actualValue, resolver)
        }.toMap()

        return JSONObjectValue(mandatoryObjectMap)
    }

    override fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String?): Pattern {
        if (additionalProperties is AdditionalProperties.FreeForm && pattern.isEmpty()) return concretePattern
        if (concretePattern !is JSONObjectPattern) throw ContractException("Expected json object type but got ${concretePattern.typeName}")

        val updatedPatternMapWithTypeAliases = concretePattern.pattern.mapValues { (key, concreteChildPattern) ->
            val originalChildPattern =
                this.pattern[key]
                    ?: this.pattern["$key?"]
                    ?: return@mapValues concreteChildPattern
            originalChildPattern.addTypeAliasesToConcretePattern(concreteChildPattern, resolver)
        }

        return concretePattern.copy(
            typeAlias = typeAlias ?: this.typeAlias,
            pattern = updatedPatternMapWithTypeAliases
        )
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver, removeExtraKeys: Boolean): ReturnValue<Value> {
        val patternToConsider = when (val resolvedPattern = resolveToPattern(value, resolver, this)) {
            is ReturnFailure -> return resolvedPattern.cast()
            else -> (resolvedPattern.value as? JSONObjectPattern) ?: return when(resolver.isNegative) {
                true -> fillInIfPatternToken(value, resolvedPattern.value, resolver)
                else -> HasFailure("Pattern is not a json object pattern")
            }
        }

        val valueToConsider = when {
            value is JSONObjectValue -> value.jsonObject
            value is StringValue && value.isPatternToken() -> {
                patternToConsider.pattern.filterNot { isOptional(it.key) }.mapValues { StringValue("(anyvalue)") }
            }
            resolver.isNegative -> return HasValue(value)
            else -> return HasFailure("Can't generate object value from type ${value.displayableType()}")
        }

        val adjustedPattern = patternToConsider.additionalProperties.updatePatternMap(
            patternMap = patternToConsider.pattern, valueMap = valueToConsider
        )

        return fill(
            jsonPatternMap = adjustedPattern, jsonValueMap = valueToConsider,
            typeAlias = patternToConsider.typeAlias, resolver = resolver,
            removeExtraKeys = removeExtraKeys
        ).realise(
            hasValue = { valuesMap, _ -> HasValue(JSONObjectValue(valuesMap)) },
            orException = { e -> e.cast() }, orFailure = { f -> f.cast() }
        )
    }

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        if (keys.isEmpty()) return this
        return this.copy(pattern = pattern.filterKeys {
            withoutOptionality(it) in keys
        }.mapKeys {
            withoutOptionality(it.key)
        })
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern {
        return this
    }

    override fun equals(other: Any?): Boolean = when (other) {
        is JSONObjectPattern -> this.pattern == other.pattern
        else -> false
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        if(value !is JSONObjectValue)
            return HasFailure(Result.Failure("Cannot resolve substitutions, expected object but got ${value.displayableType()}"))

        if(pattern.isEmpty())
            return HasValue(value)

        val updatedMap = value.jsonObject.mapValues { (key, value) ->
            val pattern = attempt("Could not find key in json object", key) { pattern[key] ?: pattern["$key?"] ?: throw MissingDataException("Could not find key $key") }
            pattern.resolveSubstitutions(substitution, value, resolver, key).breadCrumb(key)
        }

        return updatedMap.mapFoldException().ifValue { value.copy(it) }
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        if(value !is JSONObjectValue)
            return HasFailure(Result.Failure("Cannot resolve data substitutions, expected object but got ${value.displayableType()}"))

        val initialValue: ReturnValue<Map<String, Pattern>> = HasValue(emptyMap())

        return pattern.mapKeys {
            withoutOptionality(it.key)
        }.entries.fold(initialValue) { acc, (key, valuePattern) ->
            value.jsonObject[key]?.let { valueInObject ->
                val additionalTemplateTypes = valuePattern.getTemplateTypes(key, valueInObject, resolver)
                acc.assimilate(additionalTemplateTypes) { data, additional -> data + additional }
            } ?: acc
        }
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val thisResolverWithNullType = withNullPattern(thisResolver)
        val otherResolverWithNullType = withNullPattern(otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(
                listOf(this),
                otherResolverWithNullType,
                thisResolverWithNullType,
                typeStack
            )

            is TabularPattern -> {
                mapEncompassesMap(
                    pattern,
                    otherPattern.pattern,
                    thisResolverWithNullType,
                    otherResolverWithNullType,
                    typeStack
                )
            }

            is JSONObjectPattern -> {
                val propertyLimitResults: List<Result.Failure> = olderPropertyLimitsEncompassNewer(this, otherPattern)
                val patternResult = mapEncompassesMap(
                    pattern,
                    otherPattern.pattern,
                    thisResolverWithNullType,
                    otherResolverWithNullType,
                    typeStack,
                    propertyLimitResults
                )

                val additionalPropertiesResult = additionalProperties.encompasses(
                    otherPattern.additionalProperties,
                    thisResolverWithNullType,
                    otherResolverWithNullType,
                    typeStack
                )
                return Result.fromResults(listOf(patternResult, additionalPropertiesResult))
            }

            else -> Result.Failure("Expected json type, got ${otherPattern.typeName}")
        }
    }

    private fun olderPropertyLimitsEncompassNewer(
        newer: JSONObjectPattern,
        older: JSONObjectPattern
    ): List<Result.Failure> {
        val minPropertiesResult =
            if (older.minProperties != null && newer.minProperties != null && older.minProperties > newer.minProperties)
                Result.Failure("Expected at least ${older.minProperties} properties, got ${newer.minProperties}")
            else
                Result.Success()

        val maxPropertiesResult =
            if (older.maxProperties != null && newer.maxProperties != null && older.maxProperties < newer.maxProperties)
                Result.Failure("Expected at most ${older.maxProperties} properties, got ${newer.maxProperties}")
            else
                Result.Success()

        return listOf(minPropertiesResult, maxPropertiesResult).filterIsInstance<Result.Failure>()
    }

    override fun generateWithAll(resolver: Resolver): Value {
        return JSONObjectValue(pattern.filterNot { it.key == "..." }.mapKeys {
            attempt(breadCrumb = it.key) {
                withoutOptionality(it.key)
            }
        }.mapValues {
            it.value.generateWithAll(resolver)
        })
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    private fun getPatternsToCheck(pattern: Pattern, resolver: Resolver): List<Pattern> {
        return when (pattern) {
            is DeferredPattern -> getPatternsToCheck(resolvedHop(pattern, resolver), resolver)
            is ListPattern -> getPatternsToCheck(pattern.pattern, resolver)
            is AnyPattern -> pattern.pattern.flatMap { getPatternsToCheck(it, resolver) }
            else -> listOf(pattern.takeIf { it.typeAlias != null } ?: this)
        }
    }

    private fun shouldMakePropertyMandatory(pattern: Pattern, resolver: Resolver): Boolean {
        if (!resolver.allPatternsAreMandatory) return false
        val patternsToCheck = getPatternsToCheck(pattern, resolver)
        return patternsToCheck.none { resolver.hasSeenPattern(it) }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        val resolverWithNullType = withNullPattern(resolver)
        if (sampleData !is JSONObjectValue)
            return mismatchResult("JSON object", sampleData, resolver.mismatchMessages)

        val minCountErrors: List<Result.Failure> = if (sampleData.jsonObject.keys.size < (minProperties ?: 0))
            listOf(Result.Failure("Expected at least $minProperties properties, got ${sampleData.jsonObject.keys.size}"))
        else
            emptyList()

        val maxCountErrors: List<Result.Failure> =
            if (sampleData.jsonObject.keys.size > (maxProperties ?: Int.MAX_VALUE))
                listOf(Result.Failure("Expected at most $maxProperties properties, got ${sampleData.jsonObject.keys.size}"))
            else
                emptyList()

        val adjustedPattern = pattern.mapKeys {
            if (shouldMakePropertyMandatory(it.value, resolver)) {
                withoutOptionality(it.key)
            } else it.key
        }.let { additionalProperties.updatePatternMap(it, sampleData.jsonObject) }

        val keyErrors: List<Result.Failure> = resolverWithNullType.findKeyErrorList(adjustedPattern, sampleData.jsonObject).map {
            if (pattern[it.name] != null) {
                it.missingKeyToResult("key", resolver.mismatchMessages).breadCrumb(it.name)
            } else it.missingOptionalKeyToResult("key", resolver.mismatchMessages).breadCrumb(it.name)
        }

        val updatedResolver = resolverWithNullType.addPatternAsSeen(this)

        data class ResultWithDiscriminatorStatus(val result: Result, val isDiscriminator: Boolean)

        val resultsWithDiscriminator: List<ResultWithDiscriminatorStatus> =
            mapZip(adjustedPattern, sampleData.jsonObject).map { (key, patternValue, sampleValue) ->
                val result = updatedResolver.matchesPattern(key, patternValue, sampleValue).breadCrumb(key)

                val isDiscrimintor = patternValue.isDiscriminator()

                val cleanedUpResult = if(!isDiscrimintor && result is Result.Failure) {
                    result.removeReasonsFromCauses()
                } else {
                    result
                }

                ResultWithDiscriminatorStatus(cleanedUpResult, isDiscrimintor)
            }

        val results: List<Result.Failure> = resultsWithDiscriminator
            .map { it.result }
            .filterIsInstance<Result.Failure>()
            .distinctBy {
                it.reportString()
            }

        val failures: List<Result.Failure> = minCountErrors + maxCountErrors + keyErrors + results

        return if (failures.isEmpty())
            Result.Success()
        else {
            val discriminatorMatchFound = resultsWithDiscriminator.any { it.isDiscriminator && it.result.isSuccess() }

            val combinedFailure = Result.Failure.fromFailures(failures)

            if(discriminatorMatchFound)
                return combinedFailure.withFailureReason(FailureReason.FailedButDiscriminatorMatched)

            val discriminatorMisMatchFound = resultsWithDiscriminator.any { it.isDiscriminator && !it.result.isSuccess() }
            if(discriminatorMisMatchFound)
                return combinedFailure.withFailureReason(FailureReason.DiscriminatorMismatch)

            return combinedFailure.withFailureReason(FailureReason.FailedButObjectTypeMatched)
        }
    }

    override fun generate(resolver: Resolver): JSONObjectValue {
        val pattern = if (resolver.allowOnlyMandatoryKeysInJsonObject) {
            getPatternWithOmittedOptionalFields(this.pattern, resolver)
        } else {
            this.pattern
        }

        return JSONObjectValue(
            generate(
                selectPropertiesWithinMaxAndMin(pattern, minProperties, maxProperties),
                withNullPattern(resolver),
                this
            )
        )
    }

    private fun getPatternWithOmittedOptionalFields(pattern: Map<String, Pattern>, resolver: Resolver): Map<String, Pattern> {
        return pattern.filterKeys { it.endsWith("?").not() }.map { entry ->
            val (key, valuePattern) = entry

            resolvedHop(valuePattern, resolver).let { resolvedValuePattern ->
                if (resolvedValuePattern !is JSONObjectPattern) {
                    return@map entry.toPair()
                }

                key to resolvedValuePattern.copy(
                    pattern = getPatternWithOmittedOptionalFields(resolvedValuePattern.pattern, resolver)
                )
            }
        }.toMap()
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        allOrNothingCombinationIn(
            pattern.minus("..."),
            resolver.resolveRow(row),
            minProperties,
            maxProperties
        ) { pattern ->
            newMapBasedOn(pattern, row, withNullPattern(resolver))
        }.map { it: ReturnValue<Map<String, Pattern>> ->
            it.ifValue {
                toJSONObjectPattern(it.mapKeys { (key, _) ->
                    withoutOptionality(key)
                }, typeAlias)
            }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<JSONObjectPattern> =
        allOrNothingCombinationIn(
            pattern.minus("..."),
            Row(),
            null,
            null, returnValues { pattern: Map<String, Pattern> -> newBasedOn(pattern, withNullPattern(resolver)) }
        ).map { it.value }.map { toJSONObjectPattern(it, typeAlias) }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> =
        allOrNothingCombinationIn(pattern.minus("...")) { pattern ->
            AllNegativePatterns().negativeBasedOn(pattern, row, withNullPattern(resolver), config)
        }.map { it.ifValue { toJSONObjectPattern(it, typeAlias) } }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONObject(value, resolver.mismatchMessages)
    override fun hashCode(): Int = pattern.hashCode()

    fun updateWithDiscriminatorValue(discriminatorPropertyName: String, discriminatorValue: String, resolver: Resolver): ReturnValue<Pattern> {
        val actualDiscriminatorPropertyName =
            if(discriminatorPropertyName in pattern)
                discriminatorPropertyName
            else if("$discriminatorPropertyName?" in pattern)
                    "$discriminatorPropertyName?"
            else
                return HasValue(this)

        val discriminatorPattern = pattern.getValue(actualDiscriminatorPropertyName)

        if(discriminatorPattern is ExactValuePattern && discriminatorPattern.discriminator)
            return HasValue(this)

        val candidateDiscriminatorValue = discriminatorPattern.parse(discriminatorValue, resolver)
        val matchResult = discriminatorPattern.matches(candidateDiscriminatorValue, resolver)

        if(matchResult is Result.Failure)
            return HasFailure(matchResult.breadCrumb(discriminatorPropertyName))

        val updatedPattern = pattern.plus(actualDiscriminatorPropertyName to ExactValuePattern(candidateDiscriminatorValue, discriminator = true))

        return HasValue(this.copy(updatedPattern))
    }

    override val typeName: String = "json object"

    fun keysInNonOptionalFormat(): Set<String> {
        return this.pattern.map { withoutOptionality(it.key) }.toSet()
    }

    fun patternForKey(key: String): Pattern? {
        return pattern[withoutOptionality(key)] ?: pattern[withOptionality(key)]
    }

    fun calculatePath(value: Value, resolver: Resolver): Set<String> {
        if (value !is JSONObjectValue) return emptySet()
        
        return value.jsonObject.flatMap { (key, childValue) ->
            calculatePathForKey(key, childValue, resolver)
        }.toSet()
    }
    
    private fun calculatePathForKey(key: String, childValue: Value, resolver: Resolver): List<String> {
        val childPattern = patternForKey(key) ?: return emptyList()
        val resolvedPattern = resolvedHop(childPattern, resolver)
        
        return when (resolvedPattern) {
            is AnyPattern -> calculatePathForAnyPattern(key, childValue, resolvedPattern, resolver)
            is JSONObjectPattern -> calculatePathForJSONObjectPattern(key, childValue, resolvedPattern, resolver)
            is JSONArrayPattern, is ListPattern -> calculatePathForArrayPattern(key, childValue, resolvedPattern, resolver)
            else -> emptyList()
        }
    }
    

    private fun calculatePathForAnyPattern(key: String, childValue: Value, anyPattern: AnyPattern, resolver: Resolver): List<String> {
        val anyPatternPaths = anyPattern.calculatePath(childValue, resolver)
        val pathPrefix = if (!typeAlias.isNullOrBlank()) {
            val cleanTypeAlias = withoutPatternDelimiters(typeAlias)
            "{$cleanTypeAlias}.$key"
        } else {
            key
        }
        
        return if (anyPatternPaths.isNotEmpty()) {
            anyPatternPaths.map { anyPatternInfo ->
                if (anyPatternInfo.startsWith("{")) {
                    "$pathPrefix$anyPatternInfo"
                } else if (anyPatternInfo in setOf("string", "number", "boolean")) {
                    "$pathPrefix{$anyPatternInfo}"
                } else {
                    "$pathPrefix.$anyPatternInfo"
                }
            }
        } else {
            listOf(pathPrefix)
        }
    }
    
    private fun calculatePathForJSONObjectPattern(key: String, childValue: Value, objectPattern: JSONObjectPattern, resolver: Resolver): List<String> {
        val nestedPaths = objectPattern.calculatePath(childValue, resolver)
        return nestedPaths.map { nestedPath ->
            if (!typeAlias.isNullOrBlank()) {
                val cleanTypeAlias = withoutPatternDelimiters(typeAlias)

                if (nestedPath.startsWith("{")) {
                    "{$cleanTypeAlias}.$key$nestedPath"
                } else {
                    "{$cleanTypeAlias}.$key.$nestedPath"
                }
            } else {
                if (nestedPath.startsWith("{")) {
                    "$key$nestedPath"
                } else {
                    "$key.$nestedPath"
                }
            }
        }
    }
    
    private fun calculatePathForArrayPattern(key: String, childValue: Value, arrayPattern: Pattern, resolver: Resolver): List<String> {
        if (childValue !is JSONArrayValue) return emptyList()
        
        return childValue.list.flatMapIndexed { index, arrayItem ->
            calculatePathForArrayItem(key, index, arrayItem, arrayPattern, resolver)
        }
    }
    
    private fun calculatePathForArrayItem(key: String, index: Int, arrayItem: Value, arrayPattern: Pattern, resolver: Resolver): List<String> {
        val elementPattern = when (arrayPattern) {
            is JSONArrayPattern -> arrayPattern.pattern.firstOrNull()
            is ListPattern -> arrayPattern.pattern
            else -> null
        } ?: return emptyList()
        
        val resolvedElementPattern = resolvedHop(elementPattern, resolver)
        
        return when (resolvedElementPattern) {
            is AnyPattern -> calculatePathForArrayAnyPattern(key, index, arrayItem, resolvedElementPattern, resolver)
            is JSONObjectPattern -> calculatePathForArrayJSONObjectPattern(key, index, arrayItem, resolvedElementPattern, resolver)
            else -> emptyList()
        }
    }
    
    private fun calculatePathForArrayAnyPattern(key: String, index: Int, arrayItem: Value, anyPattern: AnyPattern, resolver: Resolver): List<String> {
        val anyPatternPaths = anyPattern.calculatePath(arrayItem, resolver)
        
        return if (anyPatternPaths.isNotEmpty()) {
            anyPatternPaths.map { anyPath ->
                if (!typeAlias.isNullOrBlank()) {
                    val cleanTypeAlias = withoutPatternDelimiters(typeAlias)
                    if (anyPath.startsWith("{")) {
                        "{$cleanTypeAlias}.$key[$index]$anyPath"
                    } else if (anyPath in setOf("string", "number", "boolean")) {
                        "{$cleanTypeAlias}.$key[$index]{$anyPath}"
                    } else {
                        "{$cleanTypeAlias}.$key[$index].$anyPath"
                    }
                } else {
                    if (anyPath.startsWith("{")) {
                        "$key[$index]$anyPath"
                    } else if (anyPath in setOf("string", "number", "boolean")) {
                        "$key[$index]{$anyPath}"
                    } else {
                        "$key[$index].$anyPath"
                    }
                }
            }
        } else {
            val pathPrefix = if (!typeAlias.isNullOrBlank()) {
                val cleanTypeAlias = withoutPatternDelimiters(typeAlias)
                "{$cleanTypeAlias}.$key[$index]"
            } else {
                "$key[$index]"
            }
            listOf(pathPrefix)
        }
    }
    
    private fun calculatePathForArrayJSONObjectPattern(key: String, index: Int, arrayItem: Value, objectPattern: JSONObjectPattern, resolver: Resolver): List<String> {
        val nestedPaths = objectPattern.calculatePath(arrayItem, resolver)
        return nestedPaths.map { nestedPath ->
            if (!typeAlias.isNullOrBlank()) {
                val cleanTypeAlias = withoutPatternDelimiters(typeAlias)
                if (nestedPath.startsWith("{")) {
                    "{$cleanTypeAlias}.$key[$index]$nestedPath"
                } else {
                    "{$cleanTypeAlias}.$key[$index].$nestedPath"
                }
            } else {
                if (nestedPath.startsWith("{")) {
                    "$key[$index]$nestedPath"
                } else {
                    "$key[$index].$nestedPath"
                }
            }
        }
    }
}

fun generate(jsonPatternMap: Map<String, Pattern>, resolver: Resolver, jsonPattern: JSONObjectPattern): Map<String, Value> {
    val resolverWithNullType = withNullPattern(resolver)

    val optionalProps = jsonPatternMap.keys.filter { isOptional(it) }.map { withoutOptionality(it) }

    return jsonPatternMap
        .mapKeys { entry -> withoutOptionality(entry.key) }
        .mapValues { (key, pattern) ->
            attempt(breadCrumb = key) {
                val canBeOmitted = optionalProps.contains(key)

                val value = Optional.ofNullable(
                    resolverWithNullType.withCyclePrevention(
                        jsonPattern,
                        key,
                        canBeOmitted
                    ) {
                        it.generate(jsonPattern.typeAlias, key, pattern)
                    })

                if (value.isPresent || resolverWithNullType.hasSeenLookupPath(jsonPattern, key))
                    return@attempt value

                val resolverWithCycleMarker = resolverWithNullType.cyclePast(jsonPattern, key)

                val valueWithOneCycle = Optional.ofNullable(
                    resolverWithCycleMarker.withCyclePrevention(
                        jsonPattern,
                        key,
                        canBeOmitted
                    ) {
                        it.generate(jsonPattern.typeAlias, key, pattern)
                    })

                valueWithOneCycle
            }
        }
        .filterValues { it.isPresent }
        .mapValues { (_, opt) -> opt.get() }
}

fun fix(jsonPatternMap: Map<String, Pattern>, jsonValueMap: Map<String, Value>, resolver: Resolver, jsonPattern: JSONObjectPattern): Map<String, Value> {
    val defaultKeyToPatternValuePair = adjustedPattern(jsonPatternMap, resolver)
        .map { (key, pattern) -> key to Pair(pattern, NullValue) }
        .toMap()

    val keyToPatternValuePair = jsonValueMap.mapNotNull { (key, value) ->
        val pattern = jsonPatternMap[key] ?: jsonPatternMap["$key?"]
        if (pattern == null && resolver.findKeyErrorCheck.unexpectedKeyCheck is ValidateUnexpectedKeys) return@mapNotNull null
        key to Pair(pattern, value)
    }.toMap()

    val finalMap = defaultKeyToPatternValuePair.plus(keyToPatternValuePair)
    return finalMap.mapValues { (key, patternToValue) ->
        val (pattern, value) = patternToValue
        if (pattern == null) return@mapValues Optional.of(value)

        attempt(breadCrumb = key) {
            val returnNullOnCycle = jsonPatternMap.containsKey("$key?")
            val fixedValue = Optional.ofNullable(
                resolver.withCyclePrevention(jsonPattern, key, returnNullOnCycle) {
                    it.fix(jsonPattern.typeAlias, key, pattern, value)
                }
            )

            if (fixedValue.isPresent || resolver.hasSeenLookupPath(jsonPattern, key)) return@attempt fixedValue

            val resolverWithCycleMarker = resolver.cyclePast(jsonPattern, key)
            Optional.ofNullable(
                resolverWithCycleMarker.withCyclePrevention(jsonPattern, key, returnNullOnCycle) {
                    it.fix(jsonPattern.typeAlias, key, pattern, value)
                }
            )
        }
    }
    .filterValues { it.isPresent }
    .mapValues { (_, opt) -> opt.get() }
}

fun fill(jsonPatternMap: Map<String, Pattern>, jsonValueMap: Map<String, Value>, resolver: Resolver, typeAlias: String?, removeExtraKeys: Boolean = false): ReturnValue<Map<String, Value>> {
    val adjustedValue = if (removeExtraKeys) {
        val keysToRetain = jsonPatternMap.keys.map(::withoutOptionality)
        jsonValueMap.filterKeys { it in keysToRetain }
    } else jsonValueMap

    val resolvedValuesMap = adjustedValue.mapValues { (key, value) ->
        val pattern = jsonPatternMap[key] ?: jsonPatternMap["$key?"] ?: return@mapValues when {
            resolver.findKeyErrorCheck.unexpectedKeyCheck is IgnoreUnexpectedKeys -> generateIfPatternToken(typeAlias, key, value, resolver)
            resolver.isNegative -> generateIfPatternToken(typeAlias, key, value, resolver)
            else -> HasFailure<Value>(Result.Failure(resolver.mismatchMessages.unexpectedKey("key", key)))
        }.breadCrumb(key)
        val updatedResolver = resolver.updateLookupPath(typeAlias, KeyWithPattern(key, pattern))
        pattern.fillInTheBlanks(value, updatedResolver).breadCrumb(key)
    }.mapFold()

    val remainingKeysToPattern = when {
        resolver.isNegative -> emptyMap()
        resolver.allPatternsAreMandatory -> jsonPatternMap.mapKeys { withoutOptionality(it.key) }.filterKeys { it !in jsonValueMap }
        else -> jsonPatternMap.filterKeys { !isOptional(it) && it !in jsonValueMap }
    }

    val generatedValuesMap = remainingKeysToPattern.mapValues { (key, pattern) ->
        // TODO: call fillInTheBlanks so nested patterns don't generate optional keys, Need to figure cyclePrevention for that to work
        runCatching { resolver.generate(typeAlias, key, pattern) }.map(::HasValue).getOrElse(::HasException).breadCrumb(key)
    }.mapFold()

    return resolvedValuesMap.combine(generatedValuesMap) { resolvedEntries, generatedEntries ->
        resolvedEntries + generatedEntries
    }
}

private fun generateIfPatternToken(typeAlias: String?, key: String, value: Value, resolver: Resolver): ReturnValue<Value> {
    if (value !is StringValue || !value.isPatternToken()) return HasValue(value)
    return runCatching {
        val keyPattern = resolver.getPattern(value.string).takeUnless { it is AnyValuePattern } ?: StringPattern()
        resolver.updateLookupPath(typeAlias, KeyWithPattern(key, keyPattern)).generate(keyPattern)
    }.map(::HasValue).getOrElse(::HasException)
}

private fun adjustedPattern(jsonPatternMap: Map<String, Pattern>, resolver: Resolver): Map<String, Pattern> {
    if (resolver.hasPartialKeyCheck()) return emptyMap()
    if (!resolver.allPatternsAreMandatory) return jsonPatternMap.filterKeys { !isOptional(it) }
    return jsonPatternMap.mapKeys { withoutOptionality(it.key) }
}

private fun selectPropertiesWithinMaxAndMin(
    jsonPattern: Map<String, Pattern>,
    minProperties: Int?,
    maxProperties: Int?
): Map<String, Pattern> {
    val withAtMostMaxProperties = selectAtMostMaxProperties(jsonPattern, maxProperties)

    return selectAtMostMinProperties(withAtMostMaxProperties, minProperties)
}

private fun selectAtMostMinProperties(
    properties: Map<String, Pattern>,
    minProperties: Int?
): Map<String, Pattern> {
    return if (minProperties != null) {
        val mandatoryKeys = properties.keys.filter { !isOptional(it) }
        val optionalKeys = properties.keys.filter { isOptional(it) }

        if (mandatoryKeys.size >= minProperties)
            properties.filterKeys { it in mandatoryKeys }
        else {
            val countOfOptionalKeysToPick = minProperties - mandatoryKeys.size
            val selectedOptionalKeys = optionalKeys.shuffled().take(countOfOptionalKeysToPick)
            val selectedKeys = mandatoryKeys + selectedOptionalKeys

            if (selectedKeys.size < minProperties)
                throw ContractException("Cannot generate a JSON object with at least $minProperties properties as there are only ${selectedKeys.size} properties in the specification.")

            properties.filterKeys { it in selectedKeys }
        }
    } else
        properties
}


private fun selectAtMostMaxProperties(
    properties: Map<String, Pattern>,
    maxProperties: Int?
) = if (maxProperties != null) {
    val mandatoryKeys = properties.keys.filter { !isOptional(it) }
    if (mandatoryKeys.size > maxProperties)
        throw ContractException("Cannot generate a JSON object with at most $maxProperties properties as there are ${mandatoryKeys.size} mandatory properties in the specification.")

    val optionalKeys = properties.keys.filter { isOptional(it) }
    val countOfOptionalKeysToPick = maxProperties - mandatoryKeys.size
    val selectedOptionalKeys = optionalKeys.shuffled().take(countOfOptionalKeysToPick)
    val selectedKeys = mandatoryKeys + selectedOptionalKeys

    properties.filterKeys { it in selectedKeys }
} else
    properties

internal fun mapEncompassesMap(
    pattern: Map<String, Pattern>,
    otherPattern: Map<String, Pattern>,
    thisResolverWithNullType: Resolver,
    otherResolverWithNullType: Resolver,
    typeStack: TypeStack = emptySet(),
    previousResults: List<Result.Failure> = emptyList()
): Result {
    val myRequiredKeys = pattern.keys.filter { !isOptional(it) }
    val otherRequiredKeys = otherPattern.keys.filter { !isOptional(it) }

    val missingFixedKeyErrors: List<Result.Failure> =
        myRequiredKeys.filter { it !in otherRequiredKeys }.map { missingFixedKey ->
            MissingKeyError(missingFixedKey).missingKeyToResult("key", thisResolverWithNullType.mismatchMessages)
                .breadCrumb(withoutOptionality(missingFixedKey))
        }

    val keyErrors = pattern.keys.map { key ->
        val bigger = pattern.getValue(key)
        val smaller = otherPattern[key] ?: otherPattern[withoutOptionality(key)]

        when {
            smaller != null -> biggerEncompassesSmaller(
                bigger,
                smaller,
                thisResolverWithNullType,
                otherResolverWithNullType,
                typeStack
            ).breadCrumb(withoutOptionality(key))

            else -> Result.Success()
        }
    }

    return Result.fromResults(previousResults + missingFixedKeyErrors + keyErrors)
}
