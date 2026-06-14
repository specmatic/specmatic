package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.withOptionality
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

internal data class ParsedNestedObjectQueryParams(
    val remainingQueryParams: QueryParameters,
    val reconstructedObjectValues: Map<String, Value>,
    val failures: List<Result.Failure>
)

internal fun parseNestedObjectQueryParams(
    queryParams: QueryParameters,
    effectivePatterns: Map<String, Pattern>,
    nestedObjectQueryParams: List<NestedObjectQueryParam>,
    resolver: Resolver
): ParsedNestedObjectQueryParams {
    if (nestedObjectQueryParams.isEmpty()) {
        return ParsedNestedObjectQueryParams(queryParams, emptyMap(), emptyList())
    }

    val parsedPairs = queryParams.paramPairs.map { (key, value) ->
        parseNestedQueryPair(key, value, effectivePatterns, nestedObjectQueryParams, resolver)
    }

    val reconstructedObjectsByNestedParam = parsedPairs
        .filterIsInstance<NestedQueryPair.Consumed>()
        .groupBy { it.nestedQueryParam }
        .mapValues { (_, consumedPairs) ->
            consumedPairs.fold(JSONObjectValue()) { value, consumed ->
                value.insert(consumed.path, consumed.value) as JSONObjectValue
            }
        }

    val reconstructedObjectValues = reconstructedObjectsByNestedParam.flatMap { (nestedQueryParam, objectValue) ->
        val parameterName = nestedQueryParam.parameterName
        if (effectivePatterns.containsNormalizedKey(parameterName)) {
            listOf(parameterName to objectValue)
        } else {
            objectValue.jsonObject.toList()
        }
    }.toMap()

    val remainingQueryParams = QueryParameters(
        parsedPairs.mapNotNull {
            when (it) {
                is NestedQueryPair.Unconsumed -> it.pair
                is NestedQueryPair.Consumed, is NestedQueryPair.Invalid -> null
            }
        }
    )

    return ParsedNestedObjectQueryParams(
        remainingQueryParams = remainingQueryParams,
        reconstructedObjectValues = reconstructedObjectValues,
        failures = parsedPairs.filterIsInstance<NestedQueryPair.Invalid>().map { it.failure }
    )
}

private fun parseNestedQueryPair(
    key: String,
    value: String,
    effectivePatterns: Map<String, Pattern>,
    nestedObjectQueryParams: List<NestedObjectQueryParam>,
    resolver: Resolver
): NestedQueryPair {
    val matchingNestedParam = nestedObjectQueryParams.firstOrNull { it.shouldAttemptParse(key) }
        ?: return NestedQueryPair.Unconsumed(key to value)

    return parseNestedQueryPair(matchingNestedParam, key, value, effectivePatterns, resolver)
}

private fun parseNestedQueryPair(
    nestedObjectQueryParam: NestedObjectQueryParam,
    key: String,
    value: String,
    effectivePatterns: Map<String, Pattern>,
    resolver: Resolver
): NestedQueryPair {
    return try {
        val parsedPath = nestedObjectQueryParam.parseKey(key)
        val parsedValue = try {
            nestedObjectQueryParam.parseValueAt(parsedPath, value, effectivePatterns, resolver)
        } catch (exception: ContractException) {
            return NestedQueryPair.Invalid(exception.failure().withNestedObjectPathBreadcrumb(nestedObjectQueryParam, parsedPath))
        }

        NestedQueryPair.Consumed(nestedObjectQueryParam, parsedPath, parsedValue)
    } catch (exception: ContractException) {
        NestedQueryPair.Invalid(exception.toNestedQueryKeyFailure(key))
    }
}

private fun ContractException.toNestedQueryKeyFailure(key: String): Result.Failure {
    return Result.Failure(
        message = failure().reportString().ifBlank { message.orEmpty() }
    ).breadCrumb(key)
}

private sealed class NestedQueryPair {
    data class Unconsumed(val pair: Pair<String, String>) : NestedQueryPair()
    data class Consumed(
        val nestedQueryParam: NestedObjectQueryParam,
        val path: QueryObjectPath,
        val value: Value
    ) : NestedQueryPair()
    data class Invalid(val failure: Result.Failure) : NestedQueryPair()
}

private fun Map<String, Pattern>.containsNormalizedKey(key: String): Boolean {
    return containsKey(key) || containsKey(withOptionality(key))
}
