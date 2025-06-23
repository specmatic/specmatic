package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.EXTENSIBLE_QUERY_PARAMS
import io.specmatic.core.utilities.URIUtils
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import java.net.URI

data class HttpQueryParamPattern(val queryPatterns: Map<String, Pattern>, val additionalProperties: Pattern? = null) {

    val queryKeyNames = queryPatterns.keys

    fun generate(resolver: Resolver): List<Pair<String, String>> {
        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.QUERY.value)
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            queryPatterns.map { it.key.removeSuffix("?") to it.value }.flatMap { (parameterName, pattern) ->
                attempt(breadCrumb = parameterName) {
                    val generatedValue =  updatedResolver.withCyclePrevention(pattern) { it.generate(null, parameterName, pattern) }
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

    fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<HttpQueryParamPattern>> {
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            val queryParams = queryPatterns.let {
                if(additionalProperties != null)
                    it.plus(randomString(5) to additionalProperties)
                else
                    it
            }
            val patternMap = row.withoutOmittedKeys(queryParams, resolver.defaultExampleResolver)

            allOrNothingCombinationIn(patternMap, resolver.resolveRow(row)) { pattern ->
                newMapBasedOn(pattern,row,withNullPattern(resolver))
            }.map { it: ReturnValue<Map<String, Pattern>> ->
                it.ifValue {
                    HttpQueryParamPattern(it.mapKeys { entry -> withoutOptionality(entry.key) })
                }
            }
        }
    }

    fun addComplimentaryPatterns(basePatterns: Sequence<ReturnValue<HttpQueryParamPattern>>, row: Row, resolver: Resolver): Sequence<ReturnValue<HttpQueryParamPattern>> {
        return addComplimentaryPatterns(
            basePatterns.map { rValue -> rValue.ifValue { it.queryPatterns } },
            queryPatterns,
            additionalProperties,
            row,
            resolver,
            breadCrumb = BreadCrumb.PARAM_QUERY.value
        ).map { it: ReturnValue<Map<String, Pattern>> ->
            it.ifValue {
                HttpQueryParamPattern(it.mapKeys { entry -> withoutOptionality(entry.key) })
            }
        }
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
            Result.Failure.fromFailures(failures).breadCrumb(BreadCrumb.PARAM_QUERY.value)
        else
            Result.Success()
    }

    fun newBasedOn(resolver: Resolver): Sequence<HttpQueryParamPattern> {
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
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
                null,
                returnValues { entry -> newBasedOn(entry.mapKeys { withoutOptionality(it.key) }, resolver) }
            ).map {
                HttpQueryParamPattern(it.value)
            }
        }
    }

    override fun toString(): String {
        return if (queryPatterns.isNotEmpty()) {
            "?" + queryPatterns.mapKeys { it.key.removeSuffix("?") }.map { (key, value) ->
                "$key=$value"
            }.toList().joinToString(separator = "&")
        } else ""
    }

    fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration = NegativePatternConfiguration()): Sequence<ReturnValue<HttpQueryParamPattern>> {
        return returnValue(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
                val queryParams: Map<String, Pattern> = queryPatterns.let {
                    if (additionalProperties != null)
                        it.plus(randomString(5) to additionalProperties)
                    else
                        it
                }
                val patternMap = queryParams.mapValues {
                    if (it.value is QueryParameterScalarPattern) return@mapValues it.value.pattern as Pattern
                    (it.value as QueryParameterArrayPattern).pattern.firstOrNull() ?: EmptyStringPattern
                }

                allOrNothingCombinationIn(patternMap) { pattern ->
                    NegativeNonStringlyPatterns().negativeBasedOn(pattern.mapKeys { withoutOptionality(it.key) }, row, resolver, config)
                }.plus(
                    patternsWithNoRequiredKeys(patternMap, "mandatory query param not sent")
                ).map { it: ReturnValue<Map<String, Pattern>> ->
                    it.ifValue { value -> HttpQueryParamPattern(value) }
                }
            }
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
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            readFrom(queryPatterns, row, resolver, generateMandatoryEntryIfMissing).map { HasValue(it) }
        }
    }
    fun matches(row: Row, resolver: Resolver): Result {
        return matches(queryPatterns, row, resolver, "query param")
    }

    fun fixValue(queryParams: QueryParameters?, resolver: Resolver): QueryParameters {
        val adjustedQueryParams = when {
            queryParams == null || queryParams.paramPairs.isEmpty() -> QueryParameters(emptyMap())
            additionalProperties != null -> queryParams.withoutMatching(queryPatterns.keys, additionalProperties, resolver)
            else -> queryParams
        }

        val updatedResolver = if (Flags.getBooleanValue(EXTENSIBLE_QUERY_PARAMS)) {
            resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        } else resolver.withUnexpectedKeyCheck(ValidateUnexpectedKeys)

        val fixedQueryParams = fix(
            jsonPatternMap = queryPatterns, jsonValueMap = adjustedQueryParams.asValueMap(),
            resolver = updatedResolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.QUERY.value).withoutAllPatternsAsMandatory(),
            jsonPattern = JSONObjectPattern(queryPatterns, typeAlias = null)
        )

        return QueryParameters(fixedQueryParams.mapValues { it.value.toStringLiteral() })
    }

    fun fillInTheBlanks(queryParams: QueryParameters?, resolver: Resolver): ReturnValue<QueryParameters> {
        val adjustedQueryParams = when {
            queryParams == null || queryParams.paramPairs.isEmpty() -> QueryParameters(emptyMap())
            additionalProperties != null -> queryParams.withoutMatching(queryPatterns.keys, additionalProperties, resolver)
            else -> queryParams
        }

        val updatedResolver = if (Flags.getBooleanValue(EXTENSIBLE_QUERY_PARAMS)) {
            resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        } else resolver.withUnexpectedKeyCheck(ValidateUnexpectedKeys)

        val parsedQueryParams = adjustedQueryParams.asValueMap().mapValues { (key, value) ->
            val pattern = queryPatterns[key] ?: queryPatterns["${key}?"] ?: return@mapValues value
            runCatching { pattern.parse(value.toStringLiteral(), resolver) }.getOrDefault(value)
        }

        return fill(
            jsonPatternMap = queryPatterns, jsonValueMap = parsedQueryParams,
            resolver = updatedResolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.QUERY.value),
            typeAlias = null
        ).realise(
            hasValue = { valuesMap, _ -> HasValue(QueryParameters(valuesMap.mapValues { it.value.toStringLiteral() })) },
            orException = { e -> e.cast() }, orFailure = { f -> f.cast() }
        )
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
    resolver: Resolver,
    breadCrumb: String
): Sequence<ReturnValue<Map<String, Pattern>>> {
    val generatedWithoutExamples: Sequence<ReturnValue<Map<String, Pattern>>> =
        resolver
            .generation
            .fillInTheMissingMapPatterns(
                baseGeneratedPatterns.map { it.value },
                patterns,
                additionalProperties,
                row,
                resolver,
                breadCrumb
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
