package io.specmatic.core.pattern

import io.cucumber.messages.types.TableRow
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.UnexpectedKeyCheck
import io.specmatic.core.ValidateUnexpectedKeys
import io.specmatic.core.mismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.Flags.Companion.MAX_TEST_REQUEST_COMBINATIONS
import io.specmatic.core.utilities.Flags.Companion.getStringValue
import io.specmatic.core.utilities.mapZip
import io.specmatic.core.utilities.stringToPatternMap
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

fun toTabularPattern(jsonContent: String, typeAlias: String? = null): TabularPattern =
    toTabularPattern(stringToPatternMap(jsonContent), typeAlias)

fun toTabularPattern(map: Map<String, Pattern>, typeAlias: String? = null): TabularPattern {
    val missingKeyStrategy: UnexpectedKeyCheck = when ("...") {
        in map -> IgnoreUnexpectedKeys
        else -> ValidateUnexpectedKeys
    }

    return TabularPattern(map.minus("..."), missingKeyStrategy, typeAlias)
}

data class TabularPattern(
    override val pattern: Map<String, Pattern>,
    private val unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys,
    override val typeAlias: String? = null
) : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is JSONObjectValue)
            return mismatchResult("JSON object", sampleData, resolver.mismatchMessages)

        val resolverWithNullType = withNullPattern(resolver)

        val keyErrors: List<Result.Failure> =
            resolverWithNullType.findKeyErrorList(pattern, sampleData.jsonObject).map {
                it.missingKeyToResult("key", resolver.mismatchMessages).breadCrumb(it.name)
            }

        val results: List<Result.Failure> =
            mapZip(pattern, sampleData.jsonObject).map { (key, patternValue, sampleValue) ->
                resolverWithNullType.matchesPattern(key, patternValue, sampleValue).breadCrumb(key)
            }.filterIsInstance<Result.Failure>()

        val failures = keyErrors.plus(results)

        return if (failures.isEmpty())
            Result.Success()
        else
            Result.Failure.fromFailures(failures)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value = JSONArrayValue(valueList)

    override fun generate(resolver: Resolver): JSONObjectValue {
        val resolverWithNullType = withNullPattern(resolver)
        return JSONObjectValue(pattern.mapKeys { entry -> withoutOptionality(entry.key) }.mapValues { (key, pattern) ->
            attempt(breadCrumb = key) { resolverWithNullType.withCyclePrevention(pattern) {it.generate(key, pattern)} }
        })
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

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val resolverWithNullType = withNullPattern(resolver)
        return allOrNothingCombinationIn(
            pattern,
            resolver.resolveRow(row),
            null,
            null, returnValues { pattern: Map<String, Pattern> ->
                newMapBasedOn(pattern, row, resolverWithNullType).map { it.value }
            }).map { it.value }.map {
            toTabularPattern(it.mapKeys { (key, _) ->
                withoutOptionality(key)
            })
        }.map { HasValue(it) }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        val resolverWithNullType = withNullPattern(resolver)
        val allOrNothingCombinationIn =
            allOrNothingCombinationIn(
                pattern,
                Row(),
                null,
                null, returnValues { pattern: Map<String, Pattern> ->
                    newBasedOn(pattern, resolverWithNullType)
                }).map { it.value }
        return allOrNothingCombinationIn.map { toTabularPattern(it) }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return this.newBasedOn(row, resolver).map { it.value }.map { HasValue(it) }
    }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONObject(value, resolver.mismatchMessages)
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
            is TabularPattern -> mapEncompassesMap(
                pattern,
                otherPattern.pattern,
                thisResolverWithNullType,
                otherResolverWithNullType,
                typeStack
            )
            is JSONObjectPattern -> mapEncompassesMap(
                pattern,
                otherPattern.pattern,
                thisResolverWithNullType,
                otherResolverWithNullType,
                typeStack
            )
            else -> Result.Failure("Expected json type, got ${otherPattern.typeName}")
        }
    }

    override val typeName: String = "json object"
}

fun newMapBasedOn(patternMap: Map<String, Pattern>, row: Row, resolver: Resolver): Sequence<ReturnValue<Map<String, Pattern>>> {
    val patternCollection: Map<String, Sequence<ReturnValue<Pattern>>> = patternMap.mapValues { (key, pattern) ->
        attempt(breadCrumb = withoutOptionality(key)) {
            newPatternsBasedOn(row, key, pattern, resolver).map {
                it.addDetails(breadCrumb = withoutOptionality(key), message = "")
            }
        }
    }

    return patternList(patternCollection)
}

fun newBasedOn(patternMap: Map<String, Pattern>, resolver: Resolver): Sequence<Map<String, Pattern>> {
    val patternCollection = patternMap.mapValues { (key, pattern) ->
        attempt(breadCrumb = withoutOptionality(key)) {
            newBasedOn(key, pattern, resolver)
        }
    }

    return patternValues(patternCollection)
}

fun newPatternsBasedOn(row: Row, key: String, pattern: Pattern, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
    val keyWithoutOptionality = key(pattern, key)

    return when {
        row.containsField(keyWithoutOptionality) -> {
            val rowValue = row.getField(keyWithoutOptionality)

            if (isPatternToken(rowValue)) {
                val rowPattern = resolver.getPattern(rowValue)

                attempt(breadCrumb = keyWithoutOptionality) {
                    when (val result = pattern.encompasses(rowPattern, resolver, resolver)) {
                        is Result.Success -> {
                            resolver.withCyclePrevention(rowPattern, isOptional(key)) { cyclePreventedResolver ->
                                rowPattern.newBasedOn(row, cyclePreventedResolver)
                            }?:
                            // Handle cycle (represented by null value) by using empty sequence for optional properties
                            emptySequence()
                        }
                        is Result.Failure -> throw ContractException(result.toFailureReport())
                    }
                }
            } else {
                val parsedRowValue = attempt("Format error in example of \"$keyWithoutOptionality\"") {
                    resolver.parse(pattern, rowValue)
                }

                val exactValuePattern =
                    when (val matchResult = resolver.matchesPattern(null, pattern, parsedRowValue)) {
                        is Result.Failure -> throw ContractException(matchResult.toFailureReport())
                        else -> ExactValuePattern(parsedRowValue)
                    }

                val generativePatterns: Sequence<ReturnValue<Pattern>> = resolver.generatedPatternsForGenerativeTests(pattern, key)

                val sequence: Sequence<ReturnValue<Pattern>> =
                    sequenceOf(HasValue(exactValuePattern))

                val filteredGenerativePatterns: Sequence<ReturnValue<Pattern>> = generativePatterns.filterNot { generativePatternR ->
                        generativePatternR.withDefault(false) { generativePattern ->
                            generativePattern.encompasses(exactValuePattern, resolver, resolver) is Result.Success
                        }
                    }

                sequence + filteredGenerativePatterns
            }
        }
        else -> resolver.withCyclePrevention(pattern, isOptional(key)) { cyclePreventedResolver ->
            pattern.newBasedOn(row.stepDownOneLevelInJSONHierarchy(keyWithoutOptionality), cyclePreventedResolver)
        }?:
        // Handle cycle (represented by null value) by using empty list for optional properties
        emptySequence()
    }
}

fun newBasedOn(key: String, pattern: Pattern, resolver: Resolver): Sequence<Pattern> {
    return resolver.withCyclePrevention(pattern, isOptional(key)) { cyclePreventedResolver ->
        pattern.newBasedOn(cyclePreventedResolver)
    }?:
    // Handle cycle (represented by null value) by using empty list for optional properties
    emptySequence()
}

fun key(pattern: Pattern, key: String): String {
    return withoutOptionality(
        when (pattern) {
            is Keyed -> pattern.key ?: key
            else -> key
        }
    )
}

fun <ValueType> patternList(patternCollection: Map<String, Sequence<ReturnValue<ValueType>>>): Sequence<ReturnValue<Map<String, ValueType>>> {
    if (patternCollection.isEmpty())
        return sequenceOf(HasValue(emptyMap()))

    val maxTestRequestCombinations = getStringValue(MAX_TEST_REQUEST_COMBINATIONS)?.toInt() ?: Int.MAX_VALUE
    val spec = CombinationSpec(patternCollection, maxTestRequestCombinations)
    return spec.selectedCombinations
}

fun <ValueType> patternValues(patternCollection: Map<String, Sequence<ValueType>>): Sequence<Map<String, ValueType>> {
    if (patternCollection.isEmpty())
        return sequenceOf(emptyMap())

    val first = mutableMapOf<String, ValueType>()
    val ranOut = first.mapValues { false }.toMutableMap()

    val iterators = patternCollection.mapValues {
        it.value.iterator()
    }.filter {
        it.value.hasNext()
    }

    return sequence {
        while (true) {
            val nextValue = iterators.mapValues { (key, iterator) ->
                val nextValueFromIterator = if (iterator.hasNext()) {
                    val value = iterator.next()

                    first.putIfAbsent(key, value)

                    value
                } else {
                    ranOut[key] = true
                    first.getValue(key)
                }

                nextValueFromIterator
            }

            if (ranOut.size == iterators.size && ranOut.all { it.value }) {
                break
            }

            yield(nextValue)
        }
    }
}

private fun <ValueType> keyCombinations(
    valuePatternOptions: Map<String, List<ValueType>>,
    optionalSelector: (String, List<ValueType>) -> Pair<String, ValueType>
): Map<String, ValueType> {
    return valuePatternOptions
        .filterValues { it.isNotEmpty() }
        .map { (key, value) ->
            optionalSelector(key, value)
        }.toMap()
}

fun <ValueType> forEachKeyCombinationGivenRowIn(
    patternMap: Map<String, ValueType>,
    row: Row,
    resolver: Resolver,
    creator: (Map<String, ValueType>) -> Sequence<ReturnValue<Map<String, ValueType>>>
): Sequence<ReturnValue<Map<String, ValueType>>> =
    keySets(patternMap.keys.toList(), row, resolver).map { keySet ->
        patternMap.filterKeys { key -> key in keySet }
    }.map { newPattern ->
        creator(newPattern)
    }.flatten()

fun <ValueType> forEachKeyCombinationIn(
    patternMap: Map<String, ValueType>,
    row: Row,
    creator: (Map<String, ValueType>) -> Sequence<ReturnValue<Map<String, ValueType>>>
): Sequence<ReturnValue<Map<String, ValueType>>> =
    keySets(patternMap.keys.toList(), row).map { keySet ->
        patternMap.filterKeys { key -> key in keySet }
    }.map { newPattern ->
        creator(newPattern)
    }.flatten()

fun <ValueType> returnValues(function: (Map<String, ValueType>) -> Sequence<Map<String, ValueType>>): (Map<String, ValueType>) -> Sequence<ReturnValue<Map<String, ValueType>>> {
    val wrappedFunction: (Map<String, ValueType>) -> Sequence<ReturnValue<Map<String, ValueType>>> = { map ->
        function(map).map { HasValue(it) }
    }

    return wrappedFunction
}

fun <ValueType> allOrNothingCombinationIn(
    patternMap: Map<String, ValueType>,
    row: Row = Row(),
    minPropertiesOrNull: Int? = null,
    maxPropertiesOrNull: Int? = null,
    creator: (Map<String, ValueType>) -> Sequence<ReturnValue<Map<String, ValueType>>>
): Sequence<ReturnValue<Map<String, ValueType>>> {
    val keyLists = if (patternMap.keys.any { isOptional(it) }) {
        val nothingList: Set<String> =
            patternMap.keys.filter { k -> !isOptional(k) || row.containsField(withoutOptionality(k)) }.toSet()
                .let { propertyNames ->
                    minPropertiesOrNull?.let { minProperties ->
                        if (propertyNames.size >= minProperties)
                            propertyNames
                        else {
                            val remainingPropertyNames = patternMap.keys.minus(propertyNames)
                            propertyNames + remainingPropertyNames.shuffled().toList()
                                .take(minProperties - propertyNames.size).toSet()
                        }
                    } ?: propertyNames
                }

        val allList: Set<String> = patternMap.keys.let { propertyNames ->
            maxPropertiesOrNull?.let { maxProperties ->
                if (propertyNames.size <= maxProperties)
                    propertyNames
                else {
                    val remainingPropertyNames = patternMap.keys.minus(nothingList)
                    nothingList + remainingPropertyNames.shuffled().toList().take(maxProperties - nothingList.size)
                        .toSet()
                }
            } ?: propertyNames
        }

        sequenceOf(allList, nothingList).distinct()
    } else {
        sequenceOf(patternMap.keys)
    }

    val keySets: Sequence<Map<String, ValueType>> = keyLists.map { keySet ->
        patternMap.filterKeys { key -> key in keySet }
    }

    val keySetValues = keySets.map { newPattern ->
        creator(newPattern)
    }

    return keySetValues.flatten()
}

internal fun keySets(listOfKeys: List<String>, row: Row, resolver: Resolver): Sequence<List<String>> {
    if (listOfKeys.isEmpty())
        return sequenceOf(listOfKeys)

    val key = listOfKeys.last()
    val subLists = keySets(listOfKeys.dropLast(1), row)

    return subLists.flatMap { subList ->
        when {
            row.containsField(withoutOptionality(key)) ->
                resolver.generateKeySubLists(key, subList)
            isOptional(key) -> sequenceOf(subList, subList + key)
            else -> sequenceOf(subList + key)
        }
    }
}

internal fun keySets(listOfKeys: List<String>, row: Row): Sequence<List<String>> {
    if (listOfKeys.isEmpty())
        return sequenceOf(listOfKeys)

    val key = listOfKeys.last()
    val subLists = keySets(listOfKeys.dropLast(1), row)

    return subLists.flatMap { subList ->
        when {
            row.containsField(withoutOptionality(key)) -> listOf(subList + key)
            isOptional(key) -> listOf(subList, subList + key)
            else -> listOf(subList + key)
        }
    }
}

fun rowsToTabularPattern(rows: List<TableRow>, typeAlias: String? = null) =
    toTabularPattern(rows.map { it.cells }.associate { (key, value) ->
        key.value to toJSONPattern(value.value)
    }, typeAlias)

fun toJSONPattern(value: String): Pattern {
    return value.trim().let {
        val asNumber: Number? = try {
            convertToNumber(value)
        } catch (e: Throwable) {
            null
        }

        when {
            asNumber != null -> ExactValuePattern(NumberValue(asNumber))
            it.startsWith("\"") && it.endsWith("\"") ->
                ExactValuePattern(StringValue(it.removeSurrounding("\"")))
            it == "null" -> ExactValuePattern(NullValue)
            it == "true" -> ExactValuePattern(BooleanValue(true))
            it == "false" -> ExactValuePattern(BooleanValue(false))
            else -> parsedPattern(value)
        }
    }
}

fun isNumber(value: StringValue): Boolean {
    return try {
        convertToNumber(value.string)
        true
    } catch (e: ContractException) {
        false
    }
}

fun convertToNumber(value: String): Number = value.trim().let {
    stringToInt(it) ?: stringToLong(it) ?: stringToDouble(it)
    ?: throw ContractException("""Expected number, actual was "$value"""")
}

internal fun stringToInt(value: String): Int? = try {
    value.toInt()
} catch (e: Throwable) {
    null
}

internal fun stringToLong(value: String): Long? = try {
    value.toLong()
} catch (e: Throwable) {
    null
}

internal fun stringToDouble(value: String): Double? = try {
    value.toDouble()
} catch (e: Throwable) {
    null
}
