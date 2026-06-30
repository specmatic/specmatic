package io.specmatic.core

import io.specmatic.core.pattern.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.utilities.URIUtils
import io.specmatic.core.utilities.withNullPattern
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.net.URI
import kotlin.collections.contains

data class FormExplodedObjectQueryParam(
    val parameterName: String,
    val required: Boolean,
    val propertyKeys: Set<String>,
    val requiredPropertyKeys: Set<String>
)

enum class QueryParameterSourceKind {
    ScalarParameter,
    FormExplodedObjectProperty,
    NestedObjectProperty
}

data class QueryParameterSource(
    val parameterName: String,
    val propertyName: String? = null,
    val kind: QueryParameterSourceKind = if (propertyName == null) {
        QueryParameterSourceKind.ScalarParameter
    } else {
        QueryParameterSourceKind.FormExplodedObjectProperty
    }
) {
    val displayName: String = propertyName?.let { "$parameterName.$it" } ?: parameterName
}

data class QueryParameterDeclaration(
    val key: String,
    val wireKey: String,
    val pattern: Pattern,
    val source: QueryParameterSource,
    val pointer: String? = null
)

data class HttpQueryParamPattern(
    val queryPatterns: Map<String, Pattern>,
    val additionalProperties: Pattern? = null,
    val extensibleQueryParams: Boolean = false,
    val formExplodedObjectQueryParams: List<FormExplodedObjectQueryParam> = emptyList(),
    val nestedObjectQueryParams: List<NestedObjectQueryParam> = emptyList(),
    val parameterPointers: Map<String, String> = emptyMap(),
    val queryParameterDeclarations: List<QueryParameterDeclaration> = queryPatterns.toQueryParameterDeclarations()
) {

    val queryKeyNames: Set<String>
        get() = runtimeEffectiveQueryPatterns().keys

    fun nestedObjectQueryParamsByName(): Map<String, NestedObjectQueryParam> {
        return nestedObjectQueryParams.associateBy { it.parameterName }
    }

    fun generate(resolver: Resolver): List<Pair<String, String>> {
        val updatedResolver = resolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.QUERY.value)
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            generateFromDeclarations(updatedResolver).lastValuesByWireKey()
        }
    }

    private fun generateFromDeclarations(resolver: Resolver): List<List<Pair<String, String>>> {
        return queryParameterDeclarations
            .filterNot { it.source.kind == QueryParameterSourceKind.NestedObjectProperty }
            .map { declaration ->
                attempt(breadCrumb = declaration.wireKey) {
                    declaration.generateQueryParamPairs(resolver)
                }
            }
    }

    private fun QueryParameterDeclaration.generateQueryParamPairs(resolver: Resolver): List<Pair<String, String>> {
        val parameterName = wireKey
        val generatedValue = resolver.withCyclePrevention(pattern) { it.generate(null, parameterName, pattern) }
        val nestedObjectQueryParamPairs = (generatedValue as? JSONObjectValue)?.let {
            serializeNestedObjectQueryValue(parameterName, it, nestedObjectQueryParams)
        }

        return when {
            nestedObjectQueryParamPairs != null -> nestedObjectQueryParamPairs
            generatedValue is JSONArrayValue -> generatedValue.list.map { parameterName to it.toString() }
            else -> listOf(parameterName to generatedValue.toString())
        }
    }

    private fun List<List<Pair<String, String>>>.lastValuesByWireKey(): List<Pair<String, String>> {
        val valuesByWireKey = linkedMapOf<String, List<Pair<String, String>>>()

        forEach { generatedPairs ->
            generatedPairs.groupBy { it.first }.forEach { (wireKey, values) ->
                valuesByWireKey.remove(wireKey)
                valuesByWireKey[wireKey] = values
            }
        }

        return valuesByWireKey.values.flatten()
    }

    fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<HttpQueryParamPattern>> {
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            val rowWithNestedObjectQueryExamples = row.withNestedObjectQueryParamExamples(resolver)
            val queryParams = effectiveQueryPatterns(rowWithNestedObjectQueryExamples)
            val patternMap = rowWithNestedObjectQueryExamples.withoutOmittedKeys(queryParams, resolver.defaultExampleResolver)

            queryParamCombinationsRespectingFormExplodedObjects(patternMap, resolver.resolveRow(rowWithNestedObjectQueryExamples)).flatMap { pattern ->
                newMapBasedOn(pattern, rowWithNestedObjectQueryExamples, withNullPattern(resolver))
            }.map { it: ReturnValue<Map<String, Pattern>> ->
                it.ifValue {
                    HttpQueryParamPattern(
                        it.mapKeys { entry -> withoutOptionality(entry.key) },
                        additionalProperties = additionalProperties,
                        extensibleQueryParams = extensibleQueryParams,
                        formExplodedObjectQueryParams = formExplodedObjectQueryParams,
                        nestedObjectQueryParams = nestedObjectQueryParams,
                        parameterPointers = parameterPointers
                    )
                }
            }
        }
    }

    private fun Row.withNestedObjectQueryParamExamples(resolver: Resolver): Row {
        val effectivePatterns = effectiveQueryPatterns(this)
        val nestedObjectFields = activeNestedObjectQueryParams()
            .filterNot { containsField(it.parameterName) }
            .mapNotNull { nestedObjectQueryParam ->
                val nestedPairs = columnNames.zip(values).filter { (key, _) ->
                    nestedObjectQueryParam.shouldAttemptParse(key)
                }

                if (nestedPairs.isEmpty()) {
                    null
                } else {
                    nestedObjectQueryParam.parameterName to
                        nestedObjectQueryParam.reconstructObjectValueFromQueryParamPairs(nestedPairs, effectivePatterns, resolver).toStringLiteral()
                }
            }
            .toMap()

        return addFields(nestedObjectFields)
    }

    fun addComplimentaryPatterns(basePatterns: Sequence<ReturnValue<HttpQueryParamPattern>>, row: Row, resolver: Resolver): Sequence<ReturnValue<HttpQueryParamPattern>> {
        val rowWithNestedObjectQueryExamples = row.withNestedObjectQueryParamExamples(resolver)
        return addComplimentaryPatterns(
            basePatterns.map { rValue -> rValue.ifValue { it.queryPatterns } },
            effectiveQueryPatterns(row),
            null,
            rowWithNestedObjectQueryExamples,
            resolver,
            breadCrumb = BreadCrumb.PARAM_QUERY.value
        ).map { it: ReturnValue<Map<String, Pattern>> ->
            patternWithKeyCombinationDetailsFrom(it, QUERY_PARAM_KEY_ID_IN_TEST_DETAILS) { patternMap ->
                HttpQueryParamPattern(
                    patternMap.mapKeys { entry -> withoutOptionality(entry.key) },
                    additionalProperties = additionalProperties,
                    extensibleQueryParams = extensibleQueryParams,
                    formExplodedObjectQueryParams = formExplodedObjectQueryParams,
                    nestedObjectQueryParams = nestedObjectQueryParams,
                    parameterPointers = parameterPointers
                )
            }
        }
    }

    fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        val effectivePatterns = effectiveQueryPatterns(httpRequest.queryParams)
        val activeNestedObjectQueryParams = activeNestedObjectQueryParams()
        val parsedNestedObjectQueryParams = parseNestedObjectQueryParams(httpRequest.queryParams, effectivePatterns, activeNestedObjectQueryParams, resolver)
        val queryParams = if(additionalProperties != null) {
            parsedNestedObjectQueryParams.remainingQueryParams.withoutMatching(effectivePatterns.normalizedKeys(), additionalProperties, resolver)
        } else {
            parsedNestedObjectQueryParams.remainingQueryParams
        }

        val queryValueMapWithReconstructedNestedObjects =
            queryParams.asMap().mapValues { StringValue(it.value) } + parsedNestedObjectQueryParams.reconstructedObjectValues
        val keyErrors = resolver.findKeyErrorList(effectivePatterns, queryValueMapWithReconstructedNestedObjects)
        val keyErrorList: List<Result.Failure> = keyErrors.map {
            keyErrorToResult(it, effectivePatterns, httpRequest.queryParams, resolver)
                .breadCrumb(it.name, resolver.locate(parameterPointers[it.name]))
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

        val results: List<Result?> = effectivePatterns.mapNotNull { (key, parameterPattern) ->
            val keyWithoutOptionality = withoutOptionality(key)
            val reconstructedNestedObjectValue = parsedNestedObjectQueryParams.reconstructedObjectValues[keyWithoutOptionality]
            if (reconstructedNestedObjectValue != null) {
                val nestedObjectQueryParam = activeNestedObjectQueryParams.associateBy { it.parameterName }[keyWithoutOptionality]
                return@mapNotNull resolver.matchesPattern(keyWithoutOptionality, parameterPattern, reconstructedNestedObjectValue)
                    .withNestedObjectQueryKeyBreadcrumb(nestedObjectQueryParam)
            }

            val requestValues = queryParams.getValues(keyWithoutOptionality)

            if (requestValues.isEmpty()) return@mapNotNull null

            val requestValuesList = JSONArrayValue(requestValues.map {
                StringValue(it)
            })

            resolver.matchesPattern(keyWithoutOptionality, parameterPattern, requestValuesList)
                .breadCrumb(keyWithoutOptionality, resolver.locate(parameterPointers[keyWithoutOptionality]))

        }

        val failures = parsedNestedObjectQueryParams.failures.plus(keyErrorList).plus(results).filterIsInstance<Result.Failure>()

        return if (failures.isNotEmpty())
            Result.Failure.fromFailures(failures).breadCrumb(BreadCrumb.PARAM_QUERY.value)
        else
            Result.Success()
    }

    fun exactMatchFor(queryParams: QueryParameters, resolver: Resolver): HttpQueryParamPattern {
        val exactPatterns = exactQueryPatternsFrom(queryParams, resolver)
        val exactDeclarations = exactPatterns.toExactDeclarationsFrom(queryParameterDeclarations)

        return HttpQueryParamPattern(
            queryPatterns = effectiveQueryPatternsFor(exactDeclarations),
            additionalProperties = additionalProperties,
            extensibleQueryParams = extensibleQueryParams,
            formExplodedObjectQueryParams = formExplodedObjectQueryParams,
            nestedObjectQueryParams = nestedObjectQueryParams,
            parameterPointers = parameterPointers,
            queryParameterDeclarations = exactDeclarations
        )
    }

    private fun exactQueryPatternsFrom(queryParams: QueryParameters, resolver: Resolver): Map<String, Pattern> {
        val effectivePatterns = effectiveQueryPatterns(queryParams)
        val parsedNestedObjectQueryParams = parseNestedObjectQueryParams(
            queryParams,
            effectivePatterns,
            activeNestedObjectQueryParams(),
            resolver
        )

        if (parsedNestedObjectQueryParams.failures.isNotEmpty()) {
            throw ContractException(Result.Failure.fromFailures(parsedNestedObjectQueryParams.failures).toFailureReport())
        }

        val nestedObjectExactPatterns = parsedNestedObjectQueryParams.reconstructedObjectValues.mapNotNull { (key, value) ->
            val pattern = effectivePatterns[key] ?: effectivePatterns[withOptionality(key)] ?: return@mapNotNull null
            key to exactPatternForQueryValue(value, pattern, resolver)
        }.toMap()

        val paramsWithinPattern = parsedNestedObjectQueryParams.remainingQueryParams.paramPairs
            .groupBy { it.first }
            .mapNotNull { (key, values) ->
                val pattern = effectivePatterns[key] ?: effectivePatterns[withOptionality(key)] ?: return@mapNotNull null
                key to exactPatternForQueryValues(key, values.map { it.second }, pattern, resolver)
            }.toMap()

        val paramsUnaccountedFor = parsedNestedObjectQueryParams.remainingQueryParams.paramPairs.filter { (name, _) ->
            name !in paramsWithinPattern
        }.groupBy { (name, _) ->
            name
        }

        val paramsOutsidePattern = unaccountedQueryParamsToMap(paramsUnaccountedFor)

        if (additionalProperties != null) {
            val additionalPropertiesResult = paramsUnaccountedFor.map { (_, values) ->
                values.map { (_, rawValue) ->
                    val value = additionalProperties.parse(rawValue, resolver)
                    additionalProperties.matches(value, resolver)
                }
            }.flatten()

            val matchResult = Result.fromResults(additionalPropertiesResult)
            if (matchResult is Result.Failure)
                throw ContractException(matchResult.toFailureReport())
        }

        return nestedObjectExactPatterns + paramsWithinPattern + paramsOutsidePattern
    }

    private fun Map<String, Pattern>.toExactDeclarationsFrom(declarations: List<QueryParameterDeclaration>): List<QueryParameterDeclaration> {
        val patternsByKey = mapKeys { withoutOptionality(it.key) }
        val exactDeclarations = declarations.mapNotNull { declaration ->
            val key = withoutOptionality(declaration.key)
            patternsByKey[key]?.let { exactPattern ->
                declaration.copy(key = key, pattern = exactPattern)
            }
        }

        val declaredKeys = exactDeclarations.map { withoutOptionality(it.key) }.toSet()
        val additionalDeclarations = patternsByKey
            .filterKeys { it !in declaredKeys }
            .map { (key, pattern) ->
                QueryParameterDeclaration(
                    key = key,
                    wireKey = key,
                    pattern = pattern,
                    source = QueryParameterSource(key)
                )
            }

        return exactDeclarations + additionalDeclarations
    }

    private fun keyErrorToResult(keyError: KeyError, effectivePatterns: Map<String, Pattern>, queryParams: QueryParameters, resolver: Resolver): Result.Failure {
        return when {
            effectivePatterns.contains(keyError.name) ->
                missingRequiredFormExplodedObjectPropertyToResult(keyError.name, queryParams)
                    ?: keyError.missingKeyToResult("query param", resolver.mismatchMessages)
            effectivePatterns.contains(withOptionality(keyError.name)) -> keyError.missingOptionalKeyToResult("query param", resolver.mismatchMessages)
            else -> keyError.unknownKeyToResult("query param", resolver.mismatchMessages)
        }
    }

    private fun missingRequiredFormExplodedObjectPropertyToResult(missingPropertyKey: String, queryParams: QueryParameters): Result.Failure? {
        val objectQueryParam = activeFormExplodedObjectQueryParams().firstOrNull { objectQueryParam ->
            missingPropertyKey in objectQueryParam.requiredPropertyKeys &&
                objectQueryParam.propertyKeys.any(queryParams::containsKey)
        } ?: return null

        val presentPropertyKeys = objectQueryParam.propertyKeys.filter(queryParams::containsKey).sorted()
        val missingRequiredPropertyKeys = objectQueryParam.requiredPropertyKeys.filterNot(queryParams::containsKey).sorted()
        val presentProperties = propertyListMessage(presentPropertyKeys)
        val missingRequiredProperties = propertyListMessage(missingRequiredPropertyKeys)

        return Result.Failure(
            message = missingRequiredFormExplodedObjectPropertyMessage(objectQueryParam, presentProperties, missingRequiredProperties),
            ruleViolation = StandardRuleViolation.REQUIRED_PROPERTY_MISSING
        )
    }

    private fun missingRequiredFormExplodedObjectPropertyMessage(
        objectQueryParam: FormExplodedObjectQueryParam,
        presentProperties: String,
        missingRequiredProperties: String
    ): String {
        return when {
            objectQueryParam.required ->
                "The request includes $presentProperties from required form-exploded query parameter object \"${objectQueryParam.parameterName}\". Required $missingRequiredProperties must also be provided."
            else ->
                "The request includes $presentProperties from optional form-exploded query parameter object \"${objectQueryParam.parameterName}\". Since that object is present, required $missingRequiredProperties must also be provided."
        }
    }

    private fun propertyListMessage(propertyKeys: List<String>): String {
        return when (propertyKeys.size) {
            1 -> "property ${propertyKeys.single().quoted()}"
            else -> "properties ${propertyKeys.joinToString(", ") { it.quoted() }}"
        }
    }

    private fun String.quoted(): String = "\"$this\""

    fun newBasedOn(resolver: Resolver): Sequence<HttpQueryParamPattern> {
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            val queryParams = runtimeEffectiveQueryPatterns()

            queryParamCombinationsRespectingFormExplodedObjects(queryParams, Row()).flatMap { entry ->
                newBasedOn(entry.mapKeys { withoutOptionality(it.key) }, resolver)
            }.map {
                HttpQueryParamPattern(
                    it,
                    additionalProperties = additionalProperties,
                    extensibleQueryParams = extensibleQueryParams,
                    formExplodedObjectQueryParams = formExplodedObjectQueryParams,
                    nestedObjectQueryParams = nestedObjectQueryParams,
                    parameterPointers = parameterPointers
                )
            }
        }
    }

    override fun toString(): String {
        return if (queryPatterns.isNotEmpty()) {
            "?" + runtimeEffectiveQueryPatterns().mapKeys { it.key.removeSuffix("?") }.map { (key, value) ->
                "$key=$value"
            }.toList().joinToString(separator = "&")
        } else ""
    }

    fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration = NegativePatternConfiguration()): Sequence<ReturnValue<HttpQueryParamPattern>> {
        return returnValue(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
                val queryParams: Map<String, Pattern> = effectiveQueryPatterns(row).let {
                    if (additionalProperties != null)
                        it.plus(randomString(5) to additionalProperties)
                    else
                        it
                }
                val patternMap = queryParams.mapValues {
                    negativeGenerationPattern(it.value)
                }

                allOrNothingCombinationIn(patternMap) { pattern ->
                    NegativeNonStringlyPatterns().negativeBasedOn(pattern.mapKeys { withoutOptionality(it.key) }, row, resolver, config)
                }.plus(
                    patternsWithNoRequiredKeys(patternMap, "which is a mandatory query param, is not sent")
                ).map { it: ReturnValue<Map<String, Pattern>> ->
                    patternWithKeyCombinationDetailsFrom(it, QUERY_PARAM_KEY_ID_IN_TEST_DETAILS) {
                        HttpQueryParamPattern(
                            it.mapKeys { entry -> withoutOptionality(entry.key) },
                            additionalProperties = additionalProperties,
                            extensibleQueryParams = extensibleQueryParams,
                            formExplodedObjectQueryParams = formExplodedObjectQueryParams,
                            nestedObjectQueryParams = nestedObjectQueryParams,
                            parameterPointers = parameterPointers
                        )
                    }
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
    ): Sequence<ReturnValue<HttpQueryParamPattern>> {
        return attempt(breadCrumb = BreadCrumb.PARAM_QUERY.value) {
            readFrom(effectiveQueryPatterns(row), row, resolver, generateMandatoryEntryIfMissing).map { HasValue(it) }
        }.map { pattern ->
            pattern.ifValue {
                HttpQueryParamPattern(
                    pattern.value,
                    additionalProperties = additionalProperties,
                    extensibleQueryParams = extensibleQueryParams,
                    formExplodedObjectQueryParams = formExplodedObjectQueryParams,
                    nestedObjectQueryParams = nestedObjectQueryParams,
                    parameterPointers = parameterPointers
                )
            }
        }
    }
    fun matches(row: Row, resolver: Resolver): Result {
        return matches(effectiveQueryPatterns(row), row, resolver, "query param")
    }

    fun fixValue(queryParams: QueryParameters?, resolver: Resolver): QueryParameters {
        val queryParamsToFix = queryParams ?: QueryParameters(emptyMap())
        val effectivePatterns = effectiveQueryPatterns(queryParamsToFix)
        val activeNestedObjectQueryParams = activeNestedObjectQueryParams()
        val parsedNestedObjectQueryParams = parseNestedObjectQueryParams(queryParamsToFix, effectivePatterns, activeNestedObjectQueryParams, resolver)
        val additionalQueryParams = matchingAdditionalQueryParams(parsedNestedObjectQueryParams.remainingQueryParams, effectivePatterns, resolver)
        val invalidAdditionalQueryPatterns = invalidAdditionalQueryParamPatterns(parsedNestedObjectQueryParams.remainingQueryParams, effectivePatterns, resolver)
        val patternsToFix = effectivePatterns + invalidAdditionalQueryPatterns
        val adjustedQueryParams = when {
            queryParamsToFix.paramPairs.isEmpty() -> QueryParameters(emptyMap())
            additionalProperties != null -> parsedNestedObjectQueryParams.remainingQueryParams.withoutMatching(effectivePatterns.normalizedKeys(), additionalProperties, resolver)
            else -> parsedNestedObjectQueryParams.remainingQueryParams
        }

        val updatedResolver = if (extensibleQueryParams) {
            resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        } else resolver.withUnexpectedKeyCheck(ValidateUnexpectedKeys)

        val fixedQueryParams = fix(
            jsonPatternMap = patternsToFix, jsonValueMap = adjustedQueryParams.asValueMap() + parsedNestedObjectQueryParams.reconstructedObjectValues,
            resolver = updatedResolver.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.QUERY.value).withoutAllPatternsAsMandatory(),
            jsonPattern = JSONObjectPattern(patternsToFix, typeAlias = null)
        )

        return QueryParameters(serializeNestedObjectQueryValues(fixedQueryParams, activeNestedObjectQueryParams) + additionalQueryParams.paramPairs)
    }

    fun fillInTheBlanks(queryParams: QueryParameters?, resolver: Resolver): ReturnValue<QueryParameters> {
        return resolveQueryParams(queryParams = queryParams, resolver = resolver) { effectivePatterns, parsedQueryParams, updatedResolver ->
            fill(
                jsonPatternMap = effectivePatterns,
                jsonValueMap = parsedQueryParams,
                resolver = updatedResolver,
                typeAlias = null,
            )
        }
    }

    fun resolveSubstitutions(queryParams: QueryParameters?, substitution: Substitution, resolver: Resolver): ReturnValue<QueryParameters> {
        return resolveQueryParams(queryParams = queryParams, resolver = resolver) { effectivePatterns, parsedQueryParams, updatedResolver ->
            resolveSubstitutions(
                jsonPatternMap = effectivePatterns,
                jsonValueMap = parsedQueryParams,
                substitution = substitution,
                resolver = updatedResolver,
            )
        }
    }

    private fun resolveQueryParams(
        resolver: Resolver,
        queryParams: QueryParameters?,
        resolveValues: (effectivePatterns: Map<String, Pattern>, parsedQueryParams: Map<String, Value>, resolver: Resolver) -> ReturnValue<Map<String, Value>>
    ): ReturnValue<QueryParameters> {
        val queryParamsToResolve = queryParams ?: QueryParameters(emptyMap())
        val effectivePatterns = effectiveQueryPatterns(queryParamsToResolve)
        val activeNestedObjectQueryParams = activeNestedObjectQueryParams()
        val parsedNestedObjectQueryParams = parseNestedObjectQueryParams(
            queryParams = queryParamsToResolve,
            effectivePatterns = effectivePatterns,
            nestedObjectQueryParams = activeNestedObjectQueryParams,
            resolver = resolver
        )

        val additionalQueryParams = matchingAdditionalQueryParams(
            queryParams = parsedNestedObjectQueryParams.remainingQueryParams,
            effectivePatterns = effectivePatterns,
            resolver = resolver
        )

        val adjustedQueryParams = when {
            queryParamsToResolve.paramPairs.isEmpty() -> QueryParameters(emptyMap())
            additionalProperties != null -> parsedNestedObjectQueryParams.remainingQueryParams.withoutMatching(effectivePatterns.normalizedKeys(), additionalProperties, resolver)
            else -> parsedNestedObjectQueryParams.remainingQueryParams
        }

        val updatedResolver = if (extensibleQueryParams) {
            resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
        } else {
            resolver.withUnexpectedKeyCheck(ValidateUnexpectedKeys)
        }.updateLookupPath(BreadCrumb.PARAMETERS.value).updateLookupForParam(BreadCrumb.QUERY.value)

        val parsedQueryParams = adjustedQueryParams.asValueMap().mapValues { (key, value) ->
            val pattern = effectivePatterns[key] ?: effectivePatterns["$key?"] ?: return@mapValues value
            runCatching { pattern.parse(value.toStringLiteral(), resolver) }.getOrDefault(value)
        } + parsedNestedObjectQueryParams.reconstructedObjectValues

        return resolveValues(
            effectivePatterns,
            parsedQueryParams,
            updatedResolver
        ).realise(
            hasValue = { valuesMap, _ -> HasValue(QueryParameters(serializeNestedObjectQueryValues(valuesMap, activeNestedObjectQueryParams) + additionalQueryParams.paramPairs)) },
            orException = { e -> e.cast() },
            orFailure = { f -> f.cast() }
        )
    }

    private fun matchingAdditionalQueryParams(queryParams: QueryParameters, effectivePatterns: Map<String, Pattern>, resolver: Resolver): QueryParameters {
        return additionalProperties?.let { queryParams.matching(effectivePatterns.normalizedKeys(), it, resolver) }
            ?: QueryParameters(emptyMap())
    }

    private fun invalidAdditionalQueryParamPatterns(queryParams: QueryParameters, effectivePatterns: Map<String, Pattern>, resolver: Resolver): Map<String, Pattern> {
        val additionalProperties = additionalProperties ?: return emptyMap()
        val effectiveKeys = effectivePatterns.normalizedKeys()
        val validAdditionalQueryParams = queryParams.matching(effectiveKeys, additionalProperties, resolver).paramPairs.toSet()

        return queryParams.paramPairs
            .filter { (key, _) -> key !in effectiveKeys }
            .filterNot(validAdditionalQueryParams::contains)
            .associate { (key, _) -> key to additionalProperties }
    }

    private fun negativeGenerationPattern(pattern: Pattern): Pattern {
        return when (pattern) {
            is QueryParameterScalarPattern -> pattern.pattern
            is QueryParameterArrayPattern -> pattern.pattern.firstOrNull() ?: EmptyStringPattern
            else -> pattern
        }
    }

    private fun exactPatternForQueryValues(key: String, values: List<String>, pattern: Pattern, resolver: Resolver): Pattern {
        return when (pattern) {
            is QueryParameterArrayPattern -> {
                val queryParameterValuePatterns = values.map { value ->
                    exactEncompassedQueryType(value, key, pattern.pattern.first(), resolver)
                }
                QueryParameterArrayPattern(queryParameterValuePatterns, key)
            }

            is QueryParameterScalarPattern -> {
                QueryParameterScalarPattern(
                    exactEncompassedQueryType(
                        values.single(),
                        key,
                        pattern.pattern,
                        resolver
                    )
                )
            }

            else -> {
                throw ContractException("Non query type: $pattern found")
            }
        }
    }

    private fun exactPatternForQueryValue(value: Value, pattern: Pattern, resolver: Resolver): Pattern {
        return when (pattern) {
            is QueryParameterScalarPattern -> QueryParameterScalarPattern(
                pattern.pattern.patternFrom(value, resolver) { it.exactMatchElseType() }
            )
            else -> pattern.patternFrom(value, resolver) { it.exactMatchElseType() }
        }
    }

    private fun unaccountedQueryParamsToMap(paramsUnaccountedFor: Map<String, List<Pair<String, String>>>) =
        paramsUnaccountedFor.map { (name, values) ->
            val pattern = if (values.size > 1) {
                QueryParameterArrayPattern(values.map { ExactValuePattern(StringValue(it.second)) }, name)
            } else {
                QueryParameterScalarPattern(ExactValuePattern(StringValue(values.single().second)))
            }

            name to pattern
        }.toMap()

    private fun queryParamCombinationsRespectingFormExplodedObjects(patternMap: Map<String, Pattern>, row: Row): Sequence<Map<String, Pattern>> {
        return allOrNothingQueryParamCombinations(patternMap, row)
            .flatMap(::withRequiredOnlyVariantsForPresentFormExplodedObjects)
            .distinct()
    }

    private fun allOrNothingQueryParamCombinations(patternMap: Map<String, Pattern>, row: Row): Sequence<Map<String, Pattern>> {
        return allOrNothingCombinationIn(patternMap, row) { selectedPatternMap ->
            sequenceOf(HasValue(selectedPatternMap))
        }.map { it.value }
    }

    private fun withRequiredOnlyVariantsForPresentFormExplodedObjects(patternMap: Map<String, Pattern>): Sequence<Map<String, Pattern>> {
        val requiredOnlyObjectPatternMap = requiredOnlyVariantForPresentFormExplodedObjects(patternMap)
        return sequenceOf(patternMap) + listOfNotNull(requiredOnlyObjectPatternMap).asSequence()
    }

    private fun requiredOnlyVariantForPresentFormExplodedObjects(patternMap: Map<String, Pattern>): Map<String, Pattern>? {
        val requiredOnlyPatternMap = activeFormExplodedObjectQueryParams().fold(patternMap) { patterns, objectQueryParam ->
            if (objectQueryParam.requiredPropertyKeys.isEmpty()) return@fold patterns
            if (objectQueryParam.propertyKeys.none { propertyKey -> patterns.containsNormalizedKey(propertyKey) }) return@fold patterns

            val optionalPropertyKeys = objectQueryParam.propertyKeys - objectQueryParam.requiredPropertyKeys
            if (optionalPropertyKeys.isEmpty()) return@fold patterns

            val patternsWithoutOptionalObjectProperties = patterns.filterKeys { key -> withoutOptionality(key) !in optionalPropertyKeys }

            objectQueryParam.requiredPropertyKeys.fold(patternsWithoutOptionalObjectProperties) { updatedPatterns, propertyKey ->
                updatedPatterns.makeKeyMandatory(propertyKey)
            }
        }

        return requiredOnlyPatternMap.takeUnless { it == patternMap }
    }

    companion object {
        private const val QUERY_PARAM_KEY_ID_IN_TEST_DETAILS = "param"
    }

    private fun effectiveQueryPatterns(queryParams: QueryParameters): Map<String, Pattern> {
        return activeFormExplodedObjectQueryParams().fold(runtimeEffectiveQueryPatterns()) { patterns, objectQueryParam ->
            when {
                objectQueryParam.required || objectQueryParam.propertyKeys.any(queryParams::containsKey) ->
                    objectQueryParam.requiredPropertyKeys.fold(patterns) { updatedPatterns, propertyKey ->
                        updatedPatterns.makeKeyMandatory(propertyKey)
                    }
                else -> patterns
            }
        }
    }

    private fun effectiveQueryPatterns(row: Row): Map<String, Pattern> {
        return activeFormExplodedObjectQueryParams().fold(runtimeEffectiveQueryPatterns()) { patterns, objectQueryParam ->
            when {
                objectQueryParam.required || objectQueryParam.propertyKeys.any(row::containsField) ->
                    objectQueryParam.requiredPropertyKeys.fold(patterns) { updatedPatterns, propertyKey ->
                        updatedPatterns.makeKeyMandatory(propertyKey)
                    }
                else -> patterns
            }
        }
    }

    private fun activeFormExplodedObjectQueryParams(): List<FormExplodedObjectQueryParam> {
        val supersededPropertiesByParameter = supersededObjectPropertiesByParameter(QueryParameterSourceKind.FormExplodedObjectProperty)

        return formExplodedObjectQueryParams.map { objectQueryParam ->
            objectQueryParam.withoutProperties(supersededPropertiesByParameter[objectQueryParam.parameterName].orEmpty())
        }
    }

    private fun activeNestedObjectQueryParams(): List<NestedObjectQueryParam> {
        val supersededPropertiesByParameter = supersededObjectPropertiesByParameter(QueryParameterSourceKind.NestedObjectProperty)

        return nestedObjectQueryParams.map { nestedObjectQueryParam ->
            nestedObjectQueryParam.withoutRootProperties(supersededPropertiesByParameter[nestedObjectQueryParam.parameterName].orEmpty())
        }
    }

    private fun supersededObjectPropertiesByParameter(sourceKind: QueryParameterSourceKind): Map<String, Set<String>> {
        return supersededObjectPropertiesByParameter(queryParameterDeclarations, sourceKind)
    }

    private fun runtimeEffectiveQueryPatterns(): Map<String, Pattern> {
        return effectiveQueryPatternsFor(queryParameterDeclarations)
    }

    private fun FormExplodedObjectQueryParam.withoutProperties(propertyNames: Set<String>): FormExplodedObjectQueryParam {
        if (propertyNames.isEmpty()) return this

        return copy(
            propertyKeys = propertyKeys - propertyNames,
            requiredPropertyKeys = requiredPropertyKeys - propertyNames
        )
    }

    private fun Map<String, Pattern>.makeKeyMandatory(key: String): Map<String, Pattern> {
        val optionalKey = withOptionality(key)
        val pattern = this[key] ?: this[optionalKey] ?: return this
        return this.minus(optionalKey).plus(key to pattern)
    }

    private fun Map<String, Pattern>.normalizedKeys(): Set<String> {
        return keys.map(::withoutOptionality).toSet()
    }

    private fun Map<String, Pattern>.containsNormalizedKey(key: String): Boolean {
        return containsKey(key) || containsKey(withOptionality(key))
    }
}

internal fun effectiveQueryPatternsFor(
    queryParameterDeclarations: List<QueryParameterDeclaration>
): Map<String, Pattern> {
    val winnerByWireKey = winningDeclarationsByWireKey(queryParameterDeclarations)
    val effectivePatterns = winnerByWireKey.values
        .filter { declaration -> declaration.source.kind != QueryParameterSourceKind.NestedObjectProperty }
        .associate { declaration -> declaration.key to declaration.pattern }

    return supersededObjectPropertiesByParameter(
        queryParameterDeclarations,
        QueryParameterSourceKind.NestedObjectProperty,
        winnerByWireKey
    ).entries.fold(effectivePatterns) { patterns, (parameterName, propertyNames) ->
        patterns.projectNestedObjectQueryPattern(parameterName, propertyNames)
    }
}

private fun winningDeclarationsByWireKey(queryParameterDeclarations: List<QueryParameterDeclaration>): Map<String, QueryParameterDeclaration> {
    val winnerByWireKey = linkedMapOf<String, QueryParameterDeclaration>()

    queryParameterDeclarations.forEach { declaration ->
        winnerByWireKey.remove(declaration.wireKey)
        winnerByWireKey[declaration.wireKey] = declaration
    }

    return winnerByWireKey
}

private fun supersededObjectPropertiesByParameter(
    queryParameterDeclarations: List<QueryParameterDeclaration>,
    sourceKind: QueryParameterSourceKind,
    winnerByWireKey: Map<String, QueryParameterDeclaration> = winningDeclarationsByWireKey(queryParameterDeclarations)
): Map<String, Set<String>> {
    return queryParameterDeclarations
        .filter { declaration ->
            declaration.source.kind == sourceKind &&
                winnerByWireKey[declaration.wireKey] !== declaration
        }
        .mapNotNull { declaration ->
            declaration.source.propertyName?.let { propertyName -> declaration.source.parameterName to propertyName }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, propertyNames) -> propertyNames.toSet() }
}

private fun Map<String, Pattern>.projectNestedObjectQueryPattern(parameterName: String, propertyNames: Set<String>): Map<String, Pattern> {
    if (propertyNames.isEmpty()) return this

    val entry = entries.firstOrNull { (key, _) -> withoutOptionality(key) == parameterName } ?: return this
    val projectedPattern = when (val pattern = entry.value) {
        is QueryParameterObjectPattern -> pattern.excludingRootProperties(propertyNames)
        else -> QueryParameterObjectPattern(pattern, propertyNames)
    }

    return plus(entry.key to projectedPattern)
}

private fun Map<String, Pattern>.toQueryParameterDeclarations(): List<QueryParameterDeclaration> {
    return map { (key, pattern) ->
        val wireKey = withoutOptionality(key)
        QueryParameterDeclaration(
            key = key,
            wireKey = wireKey,
            pattern = pattern,
            source = QueryParameterSource(wireKey)
        )
    }
}

private fun exactEncompassedQueryType(valueString: String, key: String?, type: Pattern, resolver: Resolver): Pattern {
    return when {
        isPatternToken(valueString) -> resolvedHop(parsedPattern(valueString, key), resolver)
        StringValue(valueString).isMatcherToken() -> type.patternFrom(StringValue(valueString), resolver) { it.exactMatchElseType() }
        else -> runCatching { type.parseToType(valueString, resolver) }.getOrElse { StringValue(valueString).exactMatchElseType() }
    }
}

internal fun buildQueryPattern(
    urlPattern: URI,
    apiKeyQueryParams: Set<String> = emptySet(),
    extensibleQueryParams: Boolean = false
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
    return HttpQueryParamPattern(
        queryPattern,
        extensibleQueryParams = extensibleQueryParams
    )
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
