package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.URIUtils
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import java.net.URI

const val QUERY_PARAMS_BREADCRUMB = "QUERY-PARAMS"

data class HttpQueryParamPattern(val queryPatterns: Map<String, Pattern>, val additionalProperties: Pattern? = null) {

    val queryKeyNames = queryPatterns.keys

    fun generate(resolver: Resolver): List<Pair<String, String>> {
        return attempt(breadCrumb = "QUERY-PARAMS") {
            queryPatterns.map { it.key.removeSuffix("?") to it.value }.flatMap { (parameterName, pattern) ->
                attempt(breadCrumb = parameterName) {
                    val generatedValue =  resolver.withCyclePrevention(pattern) { it.generate("QUERY-PARAMS", parameterName, pattern) }
                    if(generatedValue is JSONArrayValue) {
                        generatedValue.list.map { parameterName to it.toString() }
                    }
                    else {
                        listOf(parameterName to generatedValue.toString())
                    }
                }
            }.let { queryParamPairs ->
                if(additionalProperties == null)
                    queryParamPairs
                else
                    queryParamPairs.plus(randomString(5) to additionalProperties.generate(resolver).toStringLiteral())
            }
        }
    }

    fun newBasedOn(
        row: Row,
        resolver: Resolver
    ): Sequence<ReturnValue<Map<String, Pattern>>> {
        val createdBasedOnExamples = attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            val queryParams = queryPatterns.let {
                if(additionalProperties != null)
                    it.plus(randomString(5) to additionalProperties)
                else
                    it
            }

            val combinations = forEachKeyCombinationIn(
                row.withoutOmittedKeys(queryParams, resolver.defaultExampleResolver),
                row
            ) { entry ->
                newMapBasedOn(entry, row, resolver)
            }

            combinations.map { pattern ->
                pattern.update {
                    it.mapKeys { withoutOptionality(it.key) }
                }
            }
        }

        return createdBasedOnExamples
    }

    fun addComplimentaryPatterns(basePatterns: Sequence<ReturnValue<Map<String, Pattern>>>, row: Row, resolver: Resolver): Sequence<ReturnValue<Map<String, Pattern>>> {
        return addComplimentaryPatterns(basePatterns, queryPatterns, additionalProperties, row, resolver)
    }

    fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        val queryParams = if(additionalProperties != null) {
            httpRequest.queryParams.withoutMatching(queryPatterns.keys, additionalProperties, resolver)
        } else {
            httpRequest.queryParams
        }

        val keyErrors =
            resolver.findKeyErrorList(queryPatterns, queryParams.asMap().mapValues { StringValue(it.value) })
        val keyErrorList: List<Result.Failure> = keyErrors.map {
            it.missingKeyToResult("query param", resolver.mismatchMessages).breadCrumb(it.name)
        }

        // 1. key is optional and request does not have the key as well
        // 2. key is mandatory and request does not have the key as well -> Result.Failure
        // 3. key in request but not in groupedPatternPairs -> Result.Failure
        // 4. key in request
        // A. key value pattern is an array
        // B. key value pattern is a scalar (not an array)
        // C. multiple pattern patternPairGroup with the same key


        // We don't need unmatched values when:
        // 1. Running contract tests
        // 2. Backward compatibility
        // 3. Stub
        // A. Setting expectation
        // B. Matching incoming request to a stub without expectations

        // Where we need unmatched values:
        // Matching incoming request to stubbed out API

        val results: List<Result?> = queryPatterns.mapNotNull { (key, parameterPattern) ->
            val requestValues = queryParams.getValues(withoutOptionality(key))

            if (requestValues.isEmpty()) return@mapNotNull null

            val keyWithoutOptionality = withoutOptionality(key)

            val requestValuesList = JSONArrayValue(requestValues.map {
                StringValue(it)
            })

            resolver.matchesPattern(keyWithoutOptionality, parameterPattern, requestValuesList).breadCrumb(keyWithoutOptionality)

        }

        val failures = keyErrorList.plus(results).filterIsInstance<Result.Failure>()

        return if (failures.isNotEmpty())
            Result.Failure.fromFailures(failures).breadCrumb(QUERY_PARAMS_BREADCRUMB)
        else
            Result.Success()
    }

    fun newBasedOn(resolver: Resolver): Sequence<Map<String, Pattern>> {
        return attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            val queryParams = queryPatterns.let {
                if(additionalProperties != null)
                    it.plus(randomString(5) to additionalProperties)
                else
                    it
            }
            allOrNothingCombinationIn(
                queryParams,
                Row(),
                null,
                null, returnValues { entry: Map<String, Pattern> ->
                    newBasedOn(entry.mapKeys { withoutOptionality(it.key) }, resolver)
                }).map { it.value }
        }
    }

    override fun toString(): String {
        return if (queryPatterns.isNotEmpty()) {
            "?" + queryPatterns.mapKeys { it.key.removeSuffix("?") }.map { (key, value) ->
                "$key=$value"
            }.toList().joinToString(separator = "&")
        } else ""
    }

    fun negativeBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Map<String, Pattern>>> = returnValue(breadCrumb = "QUERY-PARAM") {
        attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            val queryParams: Map<String, Pattern> = queryPatterns.let {
                if (additionalProperties != null)
                    it.plus(randomString(5) to additionalProperties)
                else
                    it
            }
            val patternMap = queryParams.mapValues {
                if(it.value is QueryParameterScalarPattern) return@mapValues it.value.pattern as Pattern
                (it.value as QueryParameterArrayPattern).pattern.firstOrNull() ?: EmptyStringPattern
            }

            forEachKeyCombinationIn(queryParams, row) { entry ->
                NegativeNonStringlyPatterns().negativeBasedOn(
                    entry.mapKeys { withoutOptionality(it.key) },
                    row,
                    resolver
                )
            }.plus(
                patternsWithNoRequiredKeys(patternMap, "mandatory query param not sent")
            )
        }
    }


    fun matches(uri: URI, queryParams: Map<String, String>, resolver: Resolver = Resolver()): Result {
        return matches(HttpRequest(path = uri.path, queryParametersMap =  queryParams), resolver)
    }

    fun readFrom(
        row: Row,
        resolver: Resolver,
        generateMandatoryEntryIfMissing: Boolean
    ): Sequence<ReturnValue<Map<String, Pattern>>> {
        return attempt(breadCrumb = QUERY_PARAMS_BREADCRUMB) {
            readFrom(queryPatterns, row, resolver, generateMandatoryEntryIfMissing).map { HasValue(it) }
        }
    }
    fun matches(row: Row, resolver: Resolver): Result {
        return matches(queryPatterns, row, resolver, "query param")
    }
}

internal fun buildQueryPattern(
    urlPattern: URI,
    apiKeyQueryParams: Set<String> = emptySet()
): HttpQueryParamPattern {
    val queryPattern = URIUtils.parseQuery(urlPattern.query).mapKeys {
        "${it.key}?"
    }.mapValues {
        if (isPatternToken(it.value))
            QueryParameterScalarPattern(DeferredPattern(it.value, it.key))
        else
            QueryParameterScalarPattern(ExactValuePattern(StringValue(it.value)))
    }.let { queryParams ->
        apiKeyQueryParams.associate { apiKeyQueryParam ->
            Pair("${apiKeyQueryParam}?", StringPattern())
        }.plus(queryParams)
    }
    return HttpQueryParamPattern(queryPattern)
}

fun addComplimentaryPatterns(
    baseGeneratedPatterns: Sequence<ReturnValue<Map<String, Pattern>>>,
    patterns: Map<String, Pattern>,
    additionalProperties: Pattern?,
    row: Row,
    resolver: Resolver
): Sequence<ReturnValue<Map<String, Pattern>>> {
    val generatedWithoutExamples: Sequence<ReturnValue<Map<String, Pattern>>> =
        resolver
            .generation
            .fillInTheMissingMapPatterns(
                baseGeneratedPatterns.map { it.value },
                patterns,
                additionalProperties,
                row,
                resolver
            )
            .map {
                it.update { map -> map.mapKeys { withoutOptionality(it.key) } }
            }

    return baseGeneratedPatterns + generatedWithoutExamples
}

fun matches(patterns: Map<String, Pattern>, row: Row, resolver: Resolver, paramType: String): Result {
    val results = patterns.entries.fold(emptyList<Result>()) { results, (key, pattern) ->
        val withoutOptionality = withoutOptionality(key)

        if (row.containsField(withoutOptionality)) {
            val value = row.getField(withoutOptionality)
            val patternValue = resolver.parse(pattern, value)

            results.plus(resolver.matchesPattern(withoutOptionality, pattern, patternValue))
        } else if (isOptional(key)) {
            results.plus(Result.Success())
        } else {
            results.plus(Result.Failure("Mandatory $paramType $key not found in row"))
        }
    }

    return Result.fromResults(results)
}

fun readFrom(
    patterns: Map<String, Pattern>,
    row: Row,
    resolver: Resolver,
    generateMandatoryEntryIfMissing: Boolean
): Sequence<Map<String, Pattern>> {
    val rowAsPattern = patterns.entries.fold(emptyMap<String, Pattern>()) { acc, (key, pattern) ->
        val withoutOptionality = withoutOptionality(key)

        if (row.containsField(withoutOptionality)) {
            val patternValue = resolver.parse(
                pattern,
                row.getField(withoutOptionality)
            )
            return@fold acc.plus(withoutOptionality to patternValue.exactMatchElseType())
        }

        if (isOptional(key) || generateMandatoryEntryIfMissing.not())
            return@fold acc

        acc.plus(withoutOptionality to pattern.generate(resolver).exactMatchElseType())
    }

    return sequenceOf(rowAsPattern)
}

fun patternsWithNoRequiredKeys(
    params: Map<String, Pattern>,
    omitMessage: String
): Sequence<ReturnValue<Map<String, Pattern>>> = sequence {
    params.forEach { (keyToOmit, _) ->
        if (keyToOmit.endsWith("?").not()) {
            yield(
                HasValue(
                    params.filterKeys { key -> key != keyToOmit }.mapKeys {
                        withoutOptionality(it.key)
                    },
                    omitMessage
                ).breadCrumb(keyToOmit)
            )
        }
    }
}
