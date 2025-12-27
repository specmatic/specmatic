package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.ListValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class QueryParameterArrayPattern(override val pattern: List<Pattern>, val parameterName: String): Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is ListValue) return dataTypeMismatchResult("list of ${pattern.first().typeName}", sampleData)
        val requestValues = sampleData.list.map { it.toString() }
        val initialFoldValue =
            emptyList<Result>() to requestValues.map<String, Pair<String, List<Failure>>> { value ->
                Pair(
                    value,
                    emptyList()
                )
            }

        val (matchResults, unmatchedKeys) =
            pattern
                .foldRight(initialFoldValue) { currentPattern, (matchResultsForPreviousQueryPairs, unmatchedValuesWithReasons) ->
                    val matchResultsForCurrentParameter =
                        unmatchedValuesWithReasons.map { (value, previousParameterMatchFailures) ->
                            val parsedValue = try {
                                currentPattern.parse(value, resolver)
                            } catch (e: Exception) {
                                StringValue(value)
                            }

                            val matchResult =
                                resolver.matchesPattern(parameterName, currentPattern, parsedValue)

                            matchResult to Pair(value, previousParameterMatchFailures)
                        }

                    if (matchResultsForCurrentParameter.none { it.first is Result.Success }) {
                        val consolidatedResultForCurrentPair = Result.fromResults(matchResultsForCurrentParameter.map { it.first })
                        matchResultsForPreviousQueryPairs.plus(consolidatedResultForCurrentPair) to unmatchedValuesWithReasons
                    } else {

                        // REMEMBER: We are matching one param a time against all values.
                        // This means that any value that does not match the current param may match the next
                        // param. We need to keep track of it, in case after all params are exhausted, it proves
                        // not to match any param.

                        val unmatchedValuesInThisIteration = matchResultsForCurrentParameter.filter { (result, _) ->
                            result is Failure
                        }.map { (_, parameterMismatches) ->
                            val (value, _) = parameterMismatches
                            value
                        }.toSet()

                        val currentParameterMismatches =
                            matchResultsForCurrentParameter.filter { (_, currentParameterMismatches) ->
                                val (value, _) = currentParameterMismatches
                                value in unmatchedValuesInThisIteration
                            }.map {
                                (it.first as Failure) to it.second
                            }

                        val historicalAndCurrentParameterMismatchesCombined =
                            currentParameterMismatches.map { (reasonForLatestFailure, currentParameterMismatches) ->
                                val (value, historicalFailures) = currentParameterMismatches

                                val valueMismatchReasons =
                                    historicalFailures.plus(reasonForLatestFailure)

                                Pair(value, valueMismatchReasons)
                            }

                        matchResultsForPreviousQueryPairs.plus(Result.Success()) to historicalAndCurrentParameterMismatchesCombined
                    }
                }

        val overallMatchResultForTheKey = Result.fromResults(matchResults)

        val unmatchedKeysResult = if (unmatchedKeys.isEmpty()) {
            Result.Success()
        } else {
            Result.fromResults(unmatchedKeys.flatMap { it.second })
        }

        return Result.fromResults(listOf(overallMatchResultForTheKey, unmatchedKeysResult))
    }

    override fun generate(resolver: Resolver): Value {
        val max = (2..5).random()

        return JSONArrayValue((1..max).map {
            resolver.withCyclePrevention(pattern.first(), pattern.first()::generate)
        }.map { StringValue(it.toStringLiteral()) })
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        return pattern.first().newBasedOn(row, resolver).map { it.ifValue { QueryParameterArrayPattern(listOf(it), parameterName) } }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        return pattern.first().newBasedOn(resolver).map { QueryParameterArrayPattern(listOf(it), parameterName) }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> {
        return pattern.first().negativeBasedOn(row, resolver).map { it.ifValue { QueryParameterArrayPattern(listOf(it), parameterName) } }
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return  parsedJSONArray(value, resolver.mismatchMessages)
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        if(otherPattern !is QueryParameterArrayPattern)
            return Failure(thisResolver.mismatchMessages.mismatchMessage(this.typeName, otherPattern.typeName))

        return this.pattern.first().encompasses(otherPattern.pattern.first(), thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override val typeAlias: String?
        get() = null

    override val typeName: String
        get() = "(queryParameterArray/${pattern.first().typeName})"

    override fun parseToType(valueString: String, resolver: Resolver): Pattern {
        return pattern.first().parse(valueString, resolver).exactMatchElseType()
    }
}