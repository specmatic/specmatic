package io.specmatic.core

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.AdditionalProperties
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.ListPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.QueryParameterArrayPattern
import io.specmatic.core.pattern.QueryParameterScalarPattern
import io.specmatic.core.pattern.withOptionality
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
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
    resolver: Resolver,
    collisionGroupsByWireKey: Map<String, QueryParameterCollisionGroup> = emptyMap()
): ParsedNestedObjectQueryParams {
    if (nestedObjectQueryParams.isEmpty()) {
        return ParsedNestedObjectQueryParams(queryParams, emptyMap(), emptyList())
    }

    val parsedPairs = queryParams.paramPairs.map { (key, value) ->
        val matchingNestedParam = nestedObjectQueryParams.firstOrNull { it.shouldAttemptParse(key) }
            ?: return@map NestedQueryPair.Unconsumed(key to value)

        try {
            val parsedPath = ObjectQueryKeyParser.parse(
                key = key,
                parameterName = matchingNestedParam.parameterName,
                schema = matchingNestedParam.schema,
                syntax = matchingNestedParam.syntax
            )
            if (!matchingNestedParam.isAuthoritativeOwnerOf(key, parsedPath, collisionGroupsByWireKey)) {
                return@map NestedQueryPair.Unconsumed(key to value)
            }

            val parsedValue = try {
                matchingNestedParam.parseValueAt(parsedPath, value, effectivePatterns, resolver)
            } catch (exception: ContractException) {
                return@map NestedQueryPair.Invalid(exception.failure().withNestedObjectPathBreadcrumb(matchingNestedParam, key, parsedPath))
            }

            NestedQueryPair.Consumed(matchingNestedParam, parsedPath, parsedValue)
        } catch (exception: ContractException) {
            NestedQueryPair.Invalid(
                failure = Result.Failure(
                    message = exception.failure().reportString().ifBlank { exception.message.orEmpty() }
                ).breadCrumb(key)
            )
        }
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

internal fun serializeNestedObjectQueryValues(
    valuesMap: Map<String, Value>,
    nestedObjectQueryParams: List<NestedObjectQueryParam>
): List<Pair<String, String>> {
    val nestedParameterNames = nestedObjectQueryParams.map { it.parameterName }.toSet()
    val nestedPairs = nestedObjectQueryParams.flatMap { nestedQueryParam ->
        val value = valuesMap[nestedQueryParam.parameterName] as? JSONObjectValue
            ?: return@flatMap emptyList()

        value.toQueryParamPairs(nestedQueryParam.parameterName, nestedQueryParam.syntax)
    }

    val ordinaryPairs = valuesMap
        .filterKeys { it !in nestedParameterNames }
        .map { (key, value) -> key to value.toStringLiteral() }

    return ordinaryPairs + nestedPairs
}

internal fun serializeNestedObjectQueryValue(
    parameterName: String,
    value: JSONObjectValue,
    nestedObjectQueryParams: List<NestedObjectQueryParam>
): List<Pair<String, String>>? {
    val nestedQueryParam = nestedObjectQueryParams.firstOrNull { it.parameterName == parameterName } ?: return null
    return value.toQueryParamPairs(parameterName, nestedQueryParam.syntax)
}

internal fun NestedObjectQueryParam.reconstructObjectValueFromQueryParamPairs(
    pairs: List<Pair<String, String>>,
    effectivePatterns: Map<String, Pattern> = emptyMap(),
    resolver: Resolver = Resolver()
): JSONObjectValue {
    return pairs.fold(JSONObjectValue()) { value, (key, rawValue) ->
        val path = ObjectQueryKeyParser.parse(
            key = key,
            parameterName = parameterName,
            schema = schema,
            syntax = syntax
        )
        val parsedValue = emptyContainerValueAt(path, rawValue)
            ?: parseValueAtOrString(path, rawValue, effectivePatterns, resolver)

        value.insert(path, parsedValue) as JSONObjectValue
    }
}

private fun NestedObjectQueryParam.parseValueAtOrString(
    path: QueryObjectPath,
    value: String,
    effectivePatterns: Map<String, Pattern>,
    resolver: Resolver
): Value {
    if (effectivePatterns.isEmpty()) return StringValue(value)

    return runCatching {
        parseValueAt(path, value, effectivePatterns, resolver)
    }.getOrDefault(StringValue(value))
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

internal fun NestedObjectQueryParam.shouldAttemptParse(key: String): Boolean {
    return when (syntax.root) {
        ObjectQueryRoot.ParameterNameWrapped -> key.startsWith("$parameterName[")
        ObjectQueryRoot.ParameterNameDotWrapped -> key.startsWith("$parameterName.")
        ObjectQueryRoot.Unwrapped -> schema.properties.keys.any { propertyName ->
            key == propertyName || key.startsWith("$propertyName.") || key.startsWith("$propertyName[")
        }
    }
}

private fun NestedObjectQueryParam.isAuthoritativeOwnerOf(
    key: String,
    path: QueryObjectPath,
    collisionGroupsByWireKey: Map<String, QueryParameterCollisionGroup>
): Boolean {
    val collisionGroup = collisionGroupsByWireKey[key] ?: return true
    if (syntax.root != ObjectQueryRoot.Unwrapped) return true

    val firstProperty = (path.tokens.firstOrNull() as? QueryObjectPathToken.Property)?.name ?: return true
    val owner = collisionGroup.authoritativeOwner
    return owner.kind == QueryParameterCollisionOwnerKind.FormExplodedObjectProperty &&
        owner.parameterName == parameterName &&
        owner.propertyName == firstProperty
}

private fun Map<String, Pattern>.containsNormalizedKey(key: String): Boolean {
    return containsKey(key) || containsKey(withOptionality(key))
}

private fun NestedObjectQueryParam.parseValueAt(
    path: QueryObjectPath,
    value: String,
    effectivePatterns: Map<String, Pattern>,
    resolver: Resolver
): Value {
    emptyContainerValueAt(path, value)?.let { return it }

    val pattern = leafPatternAt(path, effectivePatterns, resolver)?.nestedQueryLeafPattern() ?: return StringValue(value)
    return pattern.parse(value, resolver)
}

private fun Pattern.nestedQueryLeafPattern(): Pattern {
    return when (this) {
        is QueryParameterScalarPattern -> pattern
        is QueryParameterArrayPattern -> pattern.firstOrNull() ?: this
        else -> this
    }
}

private fun NestedObjectQueryParam.emptyContainerValueAt(path: QueryObjectPath, value: String): Value? {
    if (value.isNotEmpty()) return null

    return when (schema.schemaAt(path.tokens)) {
        is NestedQuerySchema.Object -> JSONObjectValue()
        is NestedQuerySchema.Array -> JSONArrayValue()
        NestedQuerySchema.Scalar, is NestedQuerySchema.Ambiguous, null -> null
    }
}

private fun NestedQuerySchema.schemaAt(tokens: List<QueryObjectPathToken>): NestedQuerySchema? {
    if (tokens.isEmpty()) return this

    return when (this) {
        is NestedQuerySchema.Object -> {
            val token = tokens.first() as? QueryObjectPathToken.Property ?: return null
            val childSchema = properties[token.name]
                ?: additionalProperties
                ?: if (allowsAnyAdditionalProperties) NestedQuerySchema.Scalar else null

            childSchema?.schemaAt(tokens.drop(1))
        }
        is NestedQuerySchema.Array -> {
            if (tokens.first() !is QueryObjectPathToken.Index) return null
            itemSchema.schemaAt(tokens.drop(1))
        }
        NestedQuerySchema.Scalar, is NestedQuerySchema.Ambiguous -> null
    }
}

private fun NestedObjectQueryParam.leafPatternAt(
    path: QueryObjectPath,
    effectivePatterns: Map<String, Pattern>,
    resolver: Resolver
): Pattern? {
    val firstProperty = path.tokens.firstOrNull() as? QueryObjectPathToken.Property
    if (firstProperty != null) {
        val propertyPattern = effectivePatterns[firstProperty.name] ?: effectivePatterns[withOptionality(firstProperty.name)]
        val nestedPropertyPattern = propertyPattern?.patternAt(path.tokens.drop(1), resolver)
        if (nestedPropertyPattern != null) return nestedPropertyPattern
    }

    val rootPattern = effectivePatterns[parameterName] ?: effectivePatterns[withOptionality(parameterName)]
    if (rootPattern != null) return rootPattern.patternAt(path.tokens, resolver)

    return null
}

private fun Pattern.patternAt(tokens: List<QueryObjectPathToken>, resolver: Resolver): Pattern? {
    if (tokens.isEmpty()) return this

    return when (val resolvedPattern = resolvedHop(this, resolver)) {
        is QueryParameterScalarPattern -> resolvedPattern.pattern.patternAt(tokens, resolver)
        is JSONObjectPattern -> resolvedPattern.childPattern(tokens.first())?.patternAt(tokens.drop(1), resolver)
        is ListPattern -> {
            if (tokens.first() !is QueryObjectPathToken.Index) return null
            resolvedPattern.pattern.patternAt(tokens.drop(1), resolver)
        }
        else -> null
    }
}

private fun JSONObjectPattern.childPattern(token: QueryObjectPathToken): Pattern? {
    if (token !is QueryObjectPathToken.Property) return null

    return pattern[token.name]
        ?: pattern[withOptionality(token.name)]
        ?: when (additionalProperties) {
            is AdditionalProperties.PatternConstrained -> additionalProperties.pattern
            AdditionalProperties.FreeForm, AdditionalProperties.NoAdditionalProperties -> null
        }
}

private fun Result.Failure.withNestedObjectPathBreadcrumb(nestedObjectQueryParam: NestedObjectQueryParam, key: String, path: QueryObjectPath): Result.Failure {
    if (nestedObjectQueryParam.syntax.root == ObjectQueryRoot.Unwrapped) {
        return breadCrumb(literalMapKeyBreadcrumb(key))
    }

    val breadcrumbs = listOf(nestedObjectQueryParam.parameterName) + path.tokens.map {
        when (it) {
            is QueryObjectPathToken.Property -> it.name
            is QueryObjectPathToken.Index -> "[${it.index}]"
        }
    }

    return breadcrumbs.asReversed().fold(this as Result) { result, breadcrumb ->
        result.breadCrumb(breadcrumb)
    } as Result.Failure
}

private fun JSONObjectValue.insert(path: QueryObjectPath, value: Value): Value {
    return insertAt(tokens = path.tokens, value = value)
}

private fun Value.insertAt(tokens: List<QueryObjectPathToken>, value: Value): Value {
    if (tokens.isEmpty()) return value

    return when (val token = tokens.first()) {
        is QueryObjectPathToken.Property -> {
            val currentObject = this as? JSONObjectValue ?: JSONObjectValue()
            val updatedPropertyValue = currentObject.jsonObject[token.name]
                ?.insertAt(tokens.drop(1), value)
                ?: JSONObjectValue().insertAt(tokens.drop(1), value)

            JSONObjectValue(currentObject.jsonObject + (token.name to updatedPropertyValue))
        }
        is QueryObjectPathToken.Index -> {
            val currentArray = this as? JSONArrayValue ?: JSONArrayValue()
            val paddedValues = currentArray.list.padToIndex(token.index)
            val updatedItemValue = paddedValues[token.index].insertAt(tokens.drop(1), value)

            JSONArrayValue(paddedValues.updatedAt(token.index, updatedItemValue))
        }
    }
}

private fun List<Value>.padToIndex(index: Int): List<Value> {
    return this + List((index + 1 - size).coerceAtLeast(0)) { NullValue }
}

private fun List<Value>.updatedAt(index: Int, value: Value): List<Value> {
    return mapIndexed { itemIndex, itemValue ->
        if (itemIndex == index) value else itemValue
    }
}

private fun JSONObjectValue.toQueryParamPairs(parameterName: String, syntax: ObjectQuerySyntax): List<Pair<String, String>> {
    return flattenQueryObjectValue(this, emptyList()).map { (path, value) ->
        ObjectQueryKeySerializer.serialize(path, parameterName, syntax) to value.toStringLiteral()
    }
}

private fun flattenQueryObjectValue(value: Value, path: List<QueryObjectPathToken>): List<Pair<QueryObjectPath, Value>> {
    return when (value) {
        is JSONObjectValue -> if (value.jsonObject.isEmpty() && path.isNotEmpty()) {
            listOf(QueryObjectPath(path) to StringValue(""))
        } else {
            value.jsonObject.flatMap { (propertyName, propertyValue) ->
                flattenQueryObjectValue(propertyValue, path + QueryObjectPathToken.Property(propertyName))
            }
        }
        is JSONArrayValue -> if (value.list.isEmpty() && path.isNotEmpty()) {
            listOf(QueryObjectPath(path) to StringValue(""))
        } else {
            value.list.flatMapIndexed { index, itemValue ->
                flattenQueryObjectValue(itemValue, path + QueryObjectPathToken.Index(index))
            }
        }
        is NullValue -> emptyList()
        else -> listOf(QueryObjectPath(path) to value)
    }
}
