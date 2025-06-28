package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.mismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.stringTooPatternArray
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.utilities.withNumberType
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.ListValue
import io.specmatic.core.value.Value
import java.util.*

data class JSONArrayPattern(override val pattern: List<Pattern> = emptyList(), override val typeAlias: String? = null) : Pattern, SequenceType {
    override val memberList: MemberList
        get() {
            if (pattern.isEmpty())
                return MemberList(emptyList(), null)

            if (pattern.indexOfFirst { it is RestPattern }.let { it >= 0 && it < pattern.lastIndex })
                throw ContractException("A rest operator ... can only be used in the last entry of an array.")

            return pattern.last().let { last ->
                when (last) {
                    is RestPattern -> MemberList(pattern.dropLast(1), last.pattern)
                    else -> MemberList(pattern, null)
                }
            }
        }

    constructor(jsonString: String, typeAlias: String?) : this(stringTooPatternArray(jsonString), typeAlias = typeAlias)

    @Throws(Exception::class)
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is JSONArrayValue)
            return mismatchResult(this, sampleData, resolver.mismatchMessages)

        val resolverWithNumberType = withNumberType(withNullPattern(resolver))
        val resolvedPatterns = pattern.map { resolvedHop(it, resolverWithNumberType) }

        val theOnlyPatternInTheArray = resolvedPatterns.singleOrNull()

        if(theOnlyPatternInTheArray is ListPattern || theOnlyPatternInTheArray is RestPattern) {
            return theOnlyPatternInTheArray.matches(sampleData, resolverWithNumberType)
        }

        if(resolvedPatterns.size != sampleData.list.size)
            return Result.Failure(arrayLengthMismatchMessage(resolvedPatterns.size, sampleData.list.size))

        return resolvedPatterns.asSequence().mapIndexed { index, patternValue ->
            val sampleValue = sampleData.list[index]
            resolverWithNumberType.matchesPattern(null, patternValue, sampleValue).breadCrumb("""[$index]""")
        }.find {
            it is Result.Failure
        } ?: Result.Success()
    }

    private fun arrayLengthMismatchMessage(expectedLength: Int, actualLength: Int) =
        "Expected an array of length $expectedLength, actual length $actualLength"

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override fun generate(resolver: Resolver): Value {
        val resolverWithNullType = withNullPattern(resolver)
        return JSONArrayValue(generate(this, resolverWithNullType))
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val resolverWithNullType = withNullPattern(resolver)
        val returnValues = newListBasedOn(pattern, row, resolverWithNullType)

        return returnValues.map { it.ifValue { JSONArrayPattern(it) } }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<JSONArrayPattern> {
        val resolverWithNullType = withNullPattern(resolver)
        return newBasedOn(pattern, resolverWithNullType).map { JSONArrayPattern(it) }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> = sequenceOf(NullPattern).let {
        if(pattern.size == 1)
            it.plus(pattern[0])
        else
            it
    }.map { HasValue(it) }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONArray(value, resolver.mismatchMessages)
    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val thisResolverWithNullType = withNullPattern(thisResolver)
        val otherResolverWithNullType = withNullPattern(otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolverWithNullType, thisResolverWithNullType, typeStack)
            is SequenceType -> try {
                val otherMembers = otherPattern.memberList
                val theseMembers = this.memberList

                validateInfiniteLength(otherMembers, theseMembers).ifSuccess {
                    val otherEncompassables = otherMembers.getEncompassableList(pattern.size, otherResolverWithNullType)
                    val encompassables = when {
                        otherEncompassables.size > pattern.size -> theseMembers.getEncompassableList(otherEncompassables.size, thisResolverWithNullType)
                        else -> memberList.getEncompassables(thisResolverWithNullType)
                    }

                    val results = encompassables.zip(otherEncompassables).mapIndexed { index, (bigger, smaller) ->
                        ResultWithIndex(index, biggerEncompassesSmaller(bigger, smaller, thisResolverWithNullType, otherResolverWithNullType, typeStack))
                    }

                    results.find {
                        it.result is Result.Failure
                    }?.let { result ->
                        result.result.breadCrumb("[${result.index}]")
                    } ?: Result.Success()
                }
            } catch (e: ContractException) {
                Result.Failure(e.report())
            }
            else -> Result.Failure("Expected array or list, got ${otherPattern.typeName}")
        }
    }

    private fun validateInfiniteLength(otherMembers: MemberList, theseMembers: MemberList): Result = when {
        otherMembers.isEndless() && !theseMembers.isEndless() -> Result.Failure("Finite list is not a superset of an infinite list.")
        else -> Result.Success()
    }

    override val typeName: String = "json array"

    fun calculatePath(value: Value, resolver: Resolver): Set<String> {
        if (value !is JSONArrayValue) return emptySet()
        
        return value.list.flatMapIndexed { index, arrayItem ->
            // For JSONArrayPattern with only one pattern, apply that pattern to each array element
            if (pattern.size == 1) {
                val resolvedPattern = resolvedHop(pattern[0], resolver)
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
            } else {
                // For multiple patterns, match each element with its corresponding pattern
                if (index < pattern.size) {
                    val resolvedPattern = resolvedHop(pattern[index], resolver)
                    when (resolvedPattern) {
                        is AnyPattern -> {
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
                } else {
                    emptyList()
                }
            }
        }.toSet()
    }
}

fun newListBasedOn(patterns: List<Pattern>, row: Row, resolver: Resolver): Sequence<ReturnValue<List<Pattern>>> {
    val values = patterns.mapIndexed { index, pattern ->
        attempt(breadCrumb = "[$index]") {
            resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                pattern.newBasedOn(row, cyclePreventedResolver)
            }
        }
    }.map { it.foldIntoReturnValueOfSequence().ifValue { it.map { it as Pattern? } } }

    return listCombinations(values).distinct()
}

fun newBasedOn(patterns: List<Pattern>, resolver: Resolver): Sequence<List<Pattern>> {
    val values = patterns.mapIndexed { index, pattern ->
        attempt(breadCrumb = "[$index]") {
            resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                pattern.newBasedOn(cyclePreventedResolver)
            }
        }
    }

    return listCombinations(values.map<Sequence<Pattern?>, HasValue<Sequence<Pattern?>>> { HasValue(it) }).map { it.value }
}

fun listCombinations(values: List<ReturnValue<Sequence<Pattern?>>>): Sequence<ReturnValue<List<Pattern>>> {
    if (values.isEmpty())
        return sequenceOf(HasValue(emptyList()))

    val lastValueTypesR: ReturnValue<Sequence<Pattern?>> = values.last()
    val subLists = listCombinations(values.dropLast(1))

    return subLists.map { subListR ->
        subListR.combine(lastValueTypesR) { subList, lastValueTypes ->
            lastValueTypes.map { lastValueType ->
                if (lastValueType != null)
                    subList.plus(lastValueType)
                else
                    subList
            }.toList()
        }
    }.foldToSequenceOfReturnValueList()
}

private enum class ValueSource {
    CACHE, ITERATOR
}

fun <ValueType> allOrNothingListCombinations(values: List<Sequence<ValueType?>>): Sequence<List<ValueType>> {
    if (values.none())
        return sequenceOf(emptyList())

    val iterators = values.filter {
        it.any()
    }.map {
        it.iterator()
    }

    val cacheOfFirstValue = mutableListOf<ValueType?>()

    val iteratorCache: MutableMap<Int, Iterator<ValueType?>> = mutableMapOf()

    return sequence {
        while(true) {
            val nextValuesInArray: List<Pair<ValueType?, ValueSource>> = iterators.mapIndexed { index, rawIterator ->
                val cachedIterator = if (index in iteratorCache)
                    iteratorCache.getValue(index)
                else {
                    iteratorCache[index] = rawIterator
                    rawIterator
                }

                if (cachedIterator.hasNext()) {
                    val value = cachedIterator.next()

                    if(index >= cacheOfFirstValue.size)
                        cacheOfFirstValue.add(value)

                    Pair(value, ValueSource.ITERATOR)
                } else {
                    Pair(cacheOfFirstValue[index], ValueSource.CACHE)
                }
            }

            val allIteratorsRanOut =
                nextValuesInArray
                    .all { (_, valueSource) ->
                    valueSource == ValueSource.CACHE
                }

            if (allIteratorsRanOut) break

            val nextArray = nextValuesInArray.mapNotNull { (value, _) -> value }

            yield(nextArray)
        }

    }
}

private fun generate(jsonArrayPatter: JSONArrayPattern, resolver: Resolver): List<Value> {
    return jsonArrayPatter.pattern.mapIndexed { index, pattern ->
        resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            val updatedResolver = cyclePreventedResolver.updateLookupPath(jsonArrayPatter, pattern)
            when (pattern) {
                is RestPattern -> attempt(breadCrumb = "[$index...${jsonArrayPatter.pattern.lastIndex}]") {
                    val list = updatedResolver.generate(pattern) as ListValue
                    list.list
                }
                else -> attempt(breadCrumb = "[$index]") {
                    listOf(updatedResolver.generate(pattern))
                }
            }
        }
    }.flatten()
}

const val RANDOM_NUMBER_CEILING = 10

internal fun randomNumber(max: Int) = Random().nextInt(max - 1) + 1
