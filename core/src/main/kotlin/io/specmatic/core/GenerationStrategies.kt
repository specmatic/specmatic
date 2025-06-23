package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.withoutOptionality

interface GenerationStrategies {
    fun generatedPatternsForGenerativeTests(resolver: Resolver, pattern: Pattern, key: String): Sequence<ReturnValue<Pattern>>
    fun generateHttpRequestBodies(resolver: Resolver, body: Pattern, row: Row, requestBodyAsIs: Pattern): Sequence<ReturnValue<Pattern>>
    fun generateHttpRequestBodies(resolver: Resolver, body: Pattern, row: Row): Sequence<ReturnValue<Pattern>>
    fun resolveRow(row: Row): Row
    fun generateKeySubLists(key: String, subList: List<String>): Sequence<List<String>>

    fun fillInTheMissingMapPatterns(
        newParamsList: Sequence<Map<String, Pattern>>,
        patterns: Map<String, Pattern>,
        additionalProperties: Pattern?,
        row: Row,
        resolver: Resolver,
        breadCrumb: String
    ): Sequence<ReturnValue<Map<String, Pattern>>>
}

internal fun noOverlapBetween(
    map: Map<String, Pattern>,
    otherMaps: Sequence<Map<String, Pattern>>,
    resolver: Resolver
): Map<String, Pattern>? {
    val otherMapsWithSameKeys = otherMaps.filter {
        it.keys.map(::withoutOptionality) == map.keys.map(::withoutOptionality)
    }.map {
        it.mapKeys { withoutOptionality(it.key) }
    }

    val mapWithoutOptionality = map.mapKeys { withoutOptionality(it.key) }

    val results: Sequence<Result> = otherMapsWithSameKeys.map { otherMap ->
        val valueMatchResults = otherMap.map { (key, otherPattern) ->
            val itemPattern = mapWithoutOptionality.getValue(key)

            itemPattern.encompasses(otherPattern, resolver, resolver)
        }

        Result.fromResults(valueMatchResults)
    }

    if(results.any { it is Result.Success })
        return null

    return map
}