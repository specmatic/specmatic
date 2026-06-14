package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.withOptionality
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class NestedObjectQueryParam(
    val parameterName: String,
    val required: Boolean,
    val schema: NestedQuerySchema.Object,
    val syntax: ObjectQuerySyntax
) {
    internal fun shouldAttemptParse(key: String): Boolean {
        return when (syntax.root) {
            ObjectQueryRoot.Unwrapped -> schema.couldStartWithRootProperty(key)
            else -> syntax.root.isExplicitRootFor(key, parameterName)
        }
    }

    internal fun parseKey(key: String): QueryObjectPath {
        return ObjectQueryKeyParser.parse(
            key = key,
            parameterName = parameterName,
            schema = schema,
            syntax = syntax
        )
    }

    internal fun queryKeyFor(path: QueryObjectPath): String {
        return path.serialize(parameterName, syntax)
    }

    internal fun reconstructObjectValueFromQueryParamPairs(
        pairs: List<Pair<String, String>>,
        effectivePatterns: Map<String, Pattern> = emptyMap(),
        resolver: Resolver = Resolver()
    ): JSONObjectValue {
        return pairs.fold(JSONObjectValue()) { value, (key, rawValue) ->
            val path = parseKey(key)
            val parsedValue = emptyContainerValueAt(path, rawValue)
                ?: parseValueAtOrString(path, rawValue, effectivePatterns, resolver)

            value.insert(path, parsedValue) as JSONObjectValue
        }
    }

    internal fun parseValueAt(
        path: QueryObjectPath,
        value: String,
        effectivePatterns: Map<String, Pattern>,
        resolver: Resolver
    ): Value {
        emptyContainerValueAt(path, value)?.let { return it }

        val pattern = leafPatternAt(path, effectivePatterns, resolver)?.nestedQueryLeafPattern() ?: return StringValue(value)
        return pattern.parse(value, resolver)
    }

    private fun parseValueAtOrString(
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

    private fun emptyContainerValueAt(path: QueryObjectPath, value: String): Value? {
        if (value.isNotEmpty()) return null

        return when (schema.schemaAt(path)) {
            is NestedQuerySchema.Object -> JSONObjectValue()
            is NestedQuerySchema.Array -> JSONArrayValue()
            NestedQuerySchema.Scalar, is NestedQuerySchema.Ambiguous, null -> null
        }
    }

    private fun leafPatternAt(
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
}
