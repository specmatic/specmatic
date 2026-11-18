package io.specmatic.core.pattern

import io.ktor.http.*
import io.specmatic.core.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.pattern.fold.ObjectPatternCase
import io.specmatic.core.pattern.fold.ObjectPropertyPattern
import io.specmatic.core.pattern.fold.PatternVisitor
import io.specmatic.core.substitution.ValueSubstitutionVisitor
import io.specmatic.core.utilities.mapZip
import io.specmatic.core.utilities.stringToPatternMap
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.*
import java.util.Optional

internal fun describePropertyCount(count: Int): String =
    "$count ${if (count == 1) "property" else "properties"}"

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
            return patternMap + extraKeys.associateWith { valueMap.getValue(it).deepPattern() }
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
    override val extensions: Map<String, Any> = emptyMap(),
    val propertyPointers: Map<String, String> = emptyMap(),
    val schemaPointer: String? = null
) : Pattern, PossibleJsonObjectPatternContainer {
    init {
        require(minProperties == null || minProperties >= 0) { "minProperties $minProperties cannot be less than 0" }
        require(maxProperties == null || maxProperties >= 0) { "maxProperties $maxProperties cannot be less than 0" }
        require(minimumPropertyCount <= maximumPropertyCount) {
            "minimum property count $minimumPropertyCount exceeds maximum property count $maximumPropertyCount"
        }
    }

    override fun <C, R> accept(visitor: PatternVisitor<C, R>, context: C): R {
        return visitor.objectProperties(
            case = ObjectPatternCase(
                pattern = this,
                context = context,
                additionalProperties = additionalProperties,
                rebuildPattern = { properties -> copy(pattern = properties) },
                properties = pattern.map { (name, propertyPattern) ->
                    ObjectPropertyPattern(
                        pattern = propertyPattern,
                        required = !isOptional(name),
                        name = withoutOptionality(name),
                    )
                },
            ),
        )
    }

    override fun fixValue(value: Value, resolver: Resolver): Value {
        if (resolver.matchesPattern(this, value).isSuccess()) return value
        val valueMap = (value as? JSONObjectValue)?.jsonObject.orEmpty()
        val adjustedPattern = additionalProperties.updatePatternMap(pattern, valueMap)

        val fixedValues = fix(jsonPatternMap = adjustedPattern, jsonValueMap = valueMap, resolver = resolver, jsonPattern = this)
        if (resolver.hasPartialKeyCheck() || resolver.isNegative) return JSONObjectValue(fixedValues)
        return JSONObjectValue(completeMinimumProperties(retainPropertiesWithinMaximum(fixedValues), resolver))
    }

    private fun retainPropertiesWithinMaximum(values: Map<String, Value>): Map<String, Value> {
        val maximum = maxProperties ?: return values
        val requiredKeys = pattern.keys.filterNot(::isOptional).toSet()
        val requiredValues = values.filterKeys { it in requiredKeys }
        val remainingValues = values.filterKeys { it !in requiredKeys }.entries
            .take((maximum - requiredValues.size).coerceAtLeast(0)).associate { it.toPair() }
        return requiredValues + remainingValues
    }

    private fun completeMinimumProperties(values: Map<String, Value>, resolver: Resolver): Map<String, Value> {
        val missingCount = ((minProperties ?: 0) - values.size).coerceAtLeast(0)
        if (missingCount == 0) return values

        val missingDeclaredPatterns = pattern.entries.filter { withoutOptionality(it.key) !in values }
            .take(missingCount).associate { withoutOptionality(it.key) to it.value }
        val existingPatterns = values.mapValues { it.value.deepPattern() }
        val completePatterns = withAdditionalPropertiesForMinimum(existingPatterns + missingDeclaredPatterns)
        val missingPatterns = completePatterns.filterKeys { it !in values }

        return values + generate(missingPatterns, withNullPattern(resolver), this)
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
        if (concretePattern is ExactValuePattern && concretePattern.pattern is JSONObjectValue)
            return concretePattern.copy(typeAlias = typeAlias ?: this.typeAlias)
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
            hasValue = { valuesMap, _ -> patternToConsider.completeAndValidatePropertyCount(valuesMap, resolver) },
            orException = { e -> e.cast() }, orFailure = { f -> f.cast() }
        )
    }

    private fun completeAndValidatePropertyCount(values: Map<String, Value>, resolver: Resolver): ReturnValue<Value> {
        if (resolver.isNegative || resolver.hasPartialKeyCheck()) return HasValue(JSONObjectValue(values))
        return runCatching {
            val completed = JSONObjectValue(completeMinimumProperties(values, resolver))
            checkPropertyCount(completed.jsonObject.size, resolver).toReturnValue<Value>(completed)
        }.getOrElse(::HasException)
    }

    private fun validatePropertyCount(value: JSONObjectValue, resolver: Resolver): ReturnValue<Value> {
        if (resolver.isNegative || resolver.hasPartialKeyCheck()) return HasValue(value)
        return checkPropertyCount(value.jsonObject.size, resolver).toReturnValue(value)
    }

    private fun validateSubstitutedPropertyCount(original: JSONObjectValue, substituted: JSONObjectValue, resolver: Resolver): ReturnValue<Value> {
        val validation = validatePropertyCount(substituted, resolver)
        val minimum = minProperties ?: 0
        if (validation !is HasFailure || substituted.jsonObject.size >= minimum) return validation

        val droppedKeys = original.jsonObject.keys - substituted.jsonObject.keys
        if (droppedKeys.isEmpty()) return validation

        val reason = "Dropping ${describePropertyCount(droppedKeys.size)} (${droppedKeys.joinToString(", ") { it.quote() }}) " +
            "would leave ${describePropertyCount(substituted.jsonObject.size)}, but minProperties is $minimum. " +
            "At least ${describePropertyCount(minimum)} must remain."
        return validation.copy(failure = validation.failure.copy(causes = listOf(Result.FailureCause(message = reason))))
    }

    private fun checkPropertyCount(count: Int, resolver: Resolver): Result = when {
        count < (minProperties ?: 0) -> constraintMismatchResult(
            expected = "an object with at least ${describePropertyCount(minProperties ?: 0)}",
            actual = "an object with ${describePropertyCount(count)}", mismatchMessages = resolver.mismatchMessages
        )
        count > (maxProperties ?: Int.MAX_VALUE) -> constraintMismatchResult(
            expected = "an object with at most ${describePropertyCount(maxProperties ?: Int.MAX_VALUE)}",
            actual = "an object with ${describePropertyCount(count)}", mismatchMessages = resolver.mismatchMessages
        )
        else -> Result.Success()
    }

    override fun removeKeysNotPresentIn(keys: Set<String>, resolver: Resolver): Pattern {
        if (keys.isEmpty()) return this
        val selectedPattern = pattern.filterKeys {
            withoutOptionality(it) in keys
        }.mapKeys {
            withoutOptionality(it.key)
        }
        if (maxProperties != null && selectedPattern.size > maxProperties)
            throw ContractException(
                "Cannot select ${describePropertyCount(selectedPattern.size)} because maxProperties is $maxProperties",
                ruleViolationReport = RuleViolationReport.from(StandardRuleViolation.CONSTRAINT_VIOLATION)
            )

        return copy(pattern = selectedPattern, minProperties = minProperties?.coerceAtMost(selectedPattern.size))
    }

    override fun jsonObjectPattern(resolver: Resolver): JSONObjectPattern {
        return this
    }

    override fun ensureAdditionalProperties(resolver: Resolver): JSONObjectPattern {
        if (this.additionalProperties != AdditionalProperties.NoAdditionalProperties) return this
        return this.copy(additionalProperties = AdditionalProperties.FreeForm)
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
        val resolved = substitution.substitute(value, this, resolver).unwrapOrReturn { return it.cast() }
        val resolvedValue = resolved as? JSONObjectValue ?: return HasValue(resolved)
        val adjustedPattern = additionalProperties.updatePatternMap(patternMap = pattern, valueMap = resolvedValue.jsonObject)
            .mapKeys { (key, _) -> if (key in pattern) key else withOptionality(key) }

        if (adjustedPattern.isEmpty()) return validatePropertyCount(resolvedValue, resolver)
        return resolveSubstitutions(
            resolver = resolver,
            typeAlias = typeAlias,
            substitution = substitution,
            jsonPatternMap = adjustedPattern,
            jsonValueMap = resolvedValue.jsonObject,
        ).realise(
            hasValue = { values, _ -> validateSubstitutedPropertyCount(resolvedValue, resolvedValue.copy(values), resolver) },
            orException = { it.cast() }, orFailure = { it.cast() }
        )
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
                val propertyLimitResults: List<Result.Failure> = olderPropertyLimitsEncompassNewer(
                    older = this,
                    newer = otherPattern,
                    mismatchMessages = thisResolverWithNullType.mismatchMessages
                )

                val patternResult = mapEncompassesMap(
                    pattern,
                    otherPattern.pattern,
                    thisResolverWithNullType,
                    otherResolverWithNullType,
                    typeStack,
                    propertyLimitResults,
                    otherPropertyPointers = otherPattern.propertyPointers,
                    otherSchemaPointer = otherPattern.schemaPointer,
                    thisPropertyPointers = this.propertyPointers,
                    thisSchemaPointer = this.schemaPointer
                )

                val additionalPropertiesResult = additionalProperties.encompasses(
                    otherPattern.additionalProperties,
                    thisResolverWithNullType,
                    otherResolverWithNullType,
                    typeStack
                )

                Result.fromResults(listOf(patternResult, additionalPropertiesResult))
            }

            else -> Result.Failure("Expected json type, got ${otherPattern.typeName}")
        }
    }

    private fun olderPropertyLimitsEncompassNewer(newer: JSONObjectPattern, older: JSONObjectPattern, mismatchMessages: MismatchMessages): List<Result.Failure> {
        if (older.minProperties == null && newer.minProperties == null && older.maxProperties == null && newer.maxProperties == null)
            return emptyList()

        val minPropertiesResult =
            if (older.minimumPropertyCount > newer.minimumPropertyCount)
                Result.Failure(
                    message = mismatchMessages.mismatchMessage(
                        expected = "an object with at least ${describePropertyCount(older.minimumPropertyCount)}",
                        actual = "an object with at least ${describePropertyCount(newer.minimumPropertyCount)}"
                    ),
                    ruleViolation = StandardRuleViolation.CONSTRAINT_VIOLATION
                )
            else
                Result.Success()

        val maxPropertiesResult =
            if (older.maximumPropertyCount < newer.maximumPropertyCount)
                Result.Failure(
                    message = mismatchMessages.mismatchMessage(
                        expected = older.maximumPropertyCountDescription,
                        actual = newer.maximumPropertyCountDescription
                    ),
                    ruleViolation = StandardRuleViolation.CONSTRAINT_VIOLATION
                )
            else
                Result.Success()

        return listOf(minPropertiesResult, maxPropertiesResult).filterIsInstance<Result.Failure>()
    }

    private val minimumPropertyCount: Int
        get() = maxOf(minProperties ?: 0, pattern.keys.count { !isOptional(it) })

    private val maximumPropertyCount: Int
        get() = minOf(maxProperties ?: Int.MAX_VALUE, when (additionalProperties) {
            AdditionalProperties.NoAdditionalProperties -> pattern.size
            else -> Int.MAX_VALUE
        })

    private val maximumPropertyCountDescription: String
        get() = when (maximumPropertyCount) {
            Int.MAX_VALUE -> "an object with no upper property count limit"
            else -> "an object with at most ${describePropertyCount(maximumPropertyCount)}"
        }

    override fun generateWithAll(resolver: Resolver): Value {
        val selectedPatterns = selectPropertiesForGeneration(pattern.minus("..."))
        return JSONObjectValue(selectedPatterns.mapKeys {
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
            is SubSchemaCompositePattern -> pattern.pattern.flatMap { getPatternsToCheck(it, resolver) }
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
        if (sampleData !is JSONObjectValue) return dataTypeMismatchResult(this, sampleData, resolver.mismatchMessages)

        val propertyCountErrors = listOf(checkPropertyCount(sampleData.jsonObject.size, resolver))
            .filterIsInstance<Result.Failure>()

        val adjustedPattern = pattern.mapKeys {
            if (shouldMakePropertyMandatory(it.value, resolver)) {
                withoutOptionality(it.key)
            } else it.key
        }.let { additionalProperties.updatePatternMap(it, sampleData.jsonObject) }

        val keyErrors: List<Result.Failure> = resolverWithNullType.findKeyErrorList(adjustedPattern, sampleData.jsonObject).map {
            val keyPointer = propertyPointers[it.name] ?: propertyPointers[withoutOptionality(it.name)] ?: schemaPointer
            when {
                pattern.contains(it.canonicalKey) -> it.missingKeyToResult("property", resolver.mismatchMessages)
                pattern.contains(withOptionality(it.canonicalKey)) -> it.missingOptionalKeyToResult("property", resolver.mismatchMessages)
                else -> it.unknownKeyToResult("property", resolver.mismatchMessages)
            }.breadCrumb(it.name, resolverWithNullType.locate(keyPointer))
        }

        val updatedResolver = resolverWithNullType.addPatternAsSeen(this)

        data class ResultWithDiscriminatorStatus(val result: Result, val isDiscriminator: Boolean)

        val resultsWithDiscriminator: List<ResultWithDiscriminatorStatus> =
            mapZip(adjustedPattern, sampleData.jsonObject).map { (key, patternValue, sampleValue) ->
                val pointer = propertyPointers[key] ?: propertyPointers[withoutOptionality(key)] ?: schemaPointer
                val result = updatedResolver.matchesPattern(patternValue, sampleValue).breadCrumb(key, updatedResolver.locate(pointer))

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

        val failures: List<Result.Failure> = propertyCountErrors + keyErrors + results

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
                selectPropertiesForGeneration(pattern),
                withNullPattern(resolver),
                this
            )
        )
    }

    private fun selectPropertiesForGeneration(properties: Map<String, Pattern>): Map<String, Pattern> =
        selectPropertiesWithinMaxAndMin(withAdditionalPropertiesForMinimum(properties), minProperties, maxProperties)

    private fun withAdditionalPropertiesForMinimum(properties: Map<String, Pattern>): Map<String, Pattern> {
        val missingCount = ((minProperties ?: 0) - properties.size).coerceAtLeast(0)
        if (missingCount == 0) return properties

        val valuePattern = when (val additional = additionalProperties) {
            AdditionalProperties.NoAdditionalProperties -> return properties
            AdditionalProperties.FreeForm -> StringPattern()
            is AdditionalProperties.PatternConstrained -> additional.pattern
        }
        val declaredKeys = (this.pattern.keys + properties.keys).map(::withoutOptionality).toSet()
        val additionalPatterns = generateSequence(1) { it + 1 }
            .map { "additionalProperty$it" }
            .filterNot { it in declaredKeys }
            .take(missingCount)
            .associateWith { valuePattern }

        return properties + additionalPatterns
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
            patternWithKeyCombinationDetails(it) { patternMap ->
                copy(pattern = patternMap.mapKeys { (key, _) -> withoutOptionality(key) })
            }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<JSONObjectPattern> =
        allOrNothingCombinationIn(
            pattern.minus("..."),
            Row(),
            minProperties,
            maxProperties,
            returnValues { pattern: Map<String, Pattern> -> newBasedOn(pattern, withNullPattern(resolver)) }
        ).map { it.value }.map { copy(pattern = it) }

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> =
        allOrNothingCombinationIn(pattern.minus("...")) { pattern ->
            AllNegativePatterns().negativeBasedOn(pattern, row, withNullPattern(resolver), config)
        }.map {
            patternWithKeyCombinationDetails(it) { patternMap ->
                toJSONObjectPattern(patternMap, typeAlias)
            }
        }

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

    override fun patternFrom(value: Value, resolver: Resolver, parseValueToType: (Value) -> Pattern): Pattern {
        if (isDollarMethodOrLookup(value)) return patternFromValueUsing(this, value, resolver, parseValueToType)
        if (value !is JSONObjectValue) return parseValueToType(value)
        return toJSONObjectPattern(
            value.jsonObject.mapValues {
                val keyPattern = this.patternForKey(it.key) ?: parseValueToType(it.value)
                keyPattern.patternFrom(it.value, resolver, parseValueToType)
            },
            typeAlias
        )
    }

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
            is SubSchemaCompositePattern -> calculatePathForAnyPattern(key, childValue, resolvedPattern, resolver)
            is JSONObjectPattern -> calculatePathForJSONObjectPattern(key, childValue, resolvedPattern, resolver)
            is JSONArrayPattern, is ListPattern -> calculatePathForArrayPattern(key, childValue, resolvedPattern, resolver)
            else -> emptyList()
        }
    }
    

    private fun calculatePathForAnyPattern(key: String, childValue: Value, anyPattern: SubSchemaCompositePattern, resolver: Resolver): List<String> {
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
            is SubSchemaCompositePattern -> calculatePathForArrayAnyPattern(key, index, arrayItem, resolvedElementPattern, resolver)
            is JSONObjectPattern -> calculatePathForArrayJSONObjectPattern(key, index, arrayItem, resolvedElementPattern, resolver)
            else -> emptyList()
        }
    }
    
    private fun calculatePathForArrayAnyPattern(key: String, index: Int, arrayItem: Value, anyPattern: SubSchemaCompositePattern, resolver: Resolver): List<String> {
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

    private fun patternWithKeyCombinationDetails(
        patternMap: ReturnValue<Map<String, Pattern>>,
        buildPattern: (Map<String, Pattern>) -> Pattern
    ): ReturnValue<Pattern> =
        patternMap.ifHasValue {
            val existingValueDescription = it.valueDetails.singleLineDescription()
            val keyCombinationMessage = when (it.value.keys.size) {
                this.pattern.size -> "contains all the keys"
                else -> "contains only the mandatory keys"
            }
            val message =
                when {
                    existingValueDescription.isBlank() -> keyCombinationMessage
                    else -> "$keyCombinationMessage AND the key $existingValueDescription"
                }

            HasValue(
                buildPattern(it.value),
                listOf(ValueDetails(messages = listOf(message)))
            )
        }

    private fun addToFirstStringInner(errorReport: String, resolver: Resolver): Pair<Map<String, Pattern>, Boolean> {
        val result = pattern.entries.fold(emptyMap<String, Pattern>() to false) { acc: Pair<Map<String, Pattern>, Boolean>, entry: Map.Entry<String, Pattern> ->
            val (updatedPatternMap, isAdded) = acc
            if (isAdded) {
                return@fold updatedPatternMap.plus(entry.key to entry.value) to true
            }

            val pattern = resolvedHop(entry.value, resolver)

            return@fold when {
                pattern is StringPattern && pattern.matches(StringValue(errorReport), resolver).isSuccess() -> {
                    val newPattern = ExactValuePattern(StringValue(errorReport))
                    updatedPatternMap.plus(withoutOptionality(entry.key) to newPattern) to true
                }

                pattern is JSONObjectPattern -> {
                    val (updatedNestedPattern, wasAdded) = pattern.addToFirstStringInner(errorReport, resolver)
                    updatedPatternMap.plus(entry.key to pattern.copy(pattern = updatedNestedPattern)) to wasAdded
                }

                else -> {
                    updatedPatternMap.plus(entry.key to entry.value) to false
                }
            }
        }

        return result
    }

    fun addToFirstString(errorReport: String, resolver: Resolver): JSONObjectPattern {
        return copy(pattern = addToFirstStringInner(errorReport, resolver).first)
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

    val keyErrors = resolver.findKeyErrorCheck.validateAll(jsonPatternMap, jsonValueMap)
    val fuzzyKeyErrors = keyErrors.filterIsInstance<FuzzyKeyError>().associate{ it.name to it.canonicalKey }
    val keyToPatternValuePair = jsonValueMap.mapNotNull { (key, value) ->
        val potentialKeys = listOfNotNull(key, fuzzyKeyErrors[key])
        val match = potentialKeys.firstNotNullOfOrNull { candidateKey ->
            val pattern = jsonPatternMap[withoutOptionality(candidateKey)] ?: jsonPatternMap[withOptionality(candidateKey)]
            pattern?.let { pattern -> withoutOptionality(candidateKey) to pattern }
        }

        when {
            match != null -> match.first to Pair(match.second, value)
            !resolver.findKeyErrorCheck.isExtensible -> null
            else -> key to (null to value)
        }
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
            resolver.findKeyErrorCheck.isExtensible -> generateIfPatternToken(typeAlias, key, value, resolver)
            resolver.isNegative -> generateIfPatternToken(typeAlias, key, value, resolver)
            else -> HasFailure<Value>(Result.Failure(resolver.mismatchMessages.unexpectedKey("property", key)))
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

fun resolveSubstitutions(
    resolver: Resolver,
    typeAlias: String? = null,
    substitution: Substitution,
    jsonValueMap: Map<String, Value>,
    jsonPatternMap: Map<String, Pattern>,
    findPatternKey: (Map<String, Pattern>, String) -> String? = ::findPatternKey
): ReturnValue<Map<String, Value>> {
    val resolvedValuesMap = jsonValueMap.mapNotNull { (key, value) ->
        val patternKey = findPatternKey(jsonPatternMap, key)
        val pattern = jsonPatternMap[patternKey]

        if (substitution.isDropDirective(value)) {
            if (patternKey != null && !isOptional(patternKey)) {
                return HasFailure(Result.Failure(breadCrumb = key, message = "Cannot drop mandatory key ${key.quote()}"))
            }

            return@mapNotNull null
        }

        if (pattern == null) {
            val resolvedExtraValue = ValueSubstitutionVisitor.resolve(value, substitution)
            return@mapNotNull key to resolvedExtraValue
        }

        val updatedResolver = resolver.updateLookupPath(typeAlias, KeyWithPattern(key, pattern))
        key to pattern.resolveSubstitutions(substitution, value, updatedResolver, key).breadCrumb(key)
    }.toMap()

    return resolvedValuesMap.mapFoldException()
}

private fun findPatternKey(patterns: Map<String, Pattern>, key: String): String? {
    return when {
        key in patterns -> key
        "$key?" in patterns -> "$key?"
        else -> null
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
    if (minProperties != null && withAtMostMaxProperties.size < minProperties)
        throw ContractException("Cannot generate a JSON object with at least ${describePropertyCount(minProperties)} as the specification provides only ${describePropertyCount(withAtMostMaxProperties.size)}.")

    return withAtMostMaxProperties
}


private fun selectAtMostMaxProperties(
    properties: Map<String, Pattern>,
    maxProperties: Int?
) = if (maxProperties != null) {
    val mandatoryKeys = properties.keys.filter { !isOptional(it) }
    if (mandatoryKeys.size > maxProperties)
        throw ContractException("Cannot generate a JSON object with at most ${describePropertyCount(maxProperties)} as the specification requires ${describePropertyCount(mandatoryKeys.size)}.")

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
    previousResults: List<Result.Failure> = emptyList(),
    otherPropertyPointers: Map<String, String> = emptyMap(),
    otherSchemaPointer: String? = null,
    thisPropertyPointers: Map<String, String> = emptyMap(),
    thisSchemaPointer: String? = null
): Result {
    val myRequiredKeys = pattern.keys.filter { !isOptional(it) }
    val otherRequiredKeys = otherPattern.keys.filter { !isOptional(it) }

    fun otherLocationFor(key: String) = otherResolverWithNullType.locate(
        otherPropertyPointers[key] ?: otherPropertyPointers[withoutOptionality(key)] ?: otherSchemaPointer
    )

    fun thisLocationFor(key: String) = thisResolverWithNullType.locate(
        thisPropertyPointers[key] ?: thisPropertyPointers[withoutOptionality(key)] ?: thisSchemaPointer
    )

    val otherKeysWithoutOptionality = otherPattern.keys.map(::withoutOptionality).toSet()
    val missingFixedKeyErrors: List<Result.Failure> =
        myRequiredKeys.filter { it !in otherRequiredKeys }.map { missingFixedKey ->
            // If the property still exists in `other` (just demoted to optional) the user
            // will most likely fix it by editing the new spec, so anchor on new. If the
            // property is gone from `other` entirely, anchor on old where it was defined.
            val rawKey = withoutOptionality(missingFixedKey)
            val location = if (rawKey in otherKeysWithoutOptionality) otherLocationFor(missingFixedKey)
                           else thisLocationFor(missingFixedKey)
            MissingKeyError(missingFixedKey).missingKeyToResult("property", thisResolverWithNullType.mismatchMessages)
                .breadCrumb(rawKey, location)
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
            ).breadCrumb(withoutOptionality(key), otherLocationFor(key))

            else -> Result.Success()
        }
    }

    return Result.fromResults(previousResults + missingFixedKeyErrors + keyErrors)
}
