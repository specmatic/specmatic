package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.NestedQuerySchema
import io.specmatic.core.ObjectQueryRoot
import io.specmatic.core.pattern.Pattern
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.QueryParameter

internal fun unwrappedNestedObjectScalarPropertyCollisionEntries(
    parameter: QueryParameter,
    schemaProperties: Map<String, Schema<*>>,
    schemaContext: CollectorContext,
    parameterContext: CollectorContext,
    nestedObjectQueryParam: NestedObjectQueryParam,
    parameterPointer: String?,
    requiredProperties: Set<String>,
    resolveSchema: (Schema<*>, CollectorContext) -> Pair<Schema<*>, CollectorContext>,
    toSpecmaticParamName: (Boolean, String) -> String,
    toQueryParameterPattern: (String, Schema<*>, Schema<*>, CollectorContext, CollectorContext) -> Pattern
): List<QueryParameterPatternEntry> {
    if (nestedObjectQueryParam.syntax.root != ObjectQueryRoot.Unwrapped) return emptyList()

    return schemaProperties.mapNotNull { (propertyName, propertySchema) ->
        if (nestedObjectQueryParam.schema.properties[propertyName] != NestedQuerySchema.Scalar) return@mapNotNull null

        val propertyContext = schemaContext.at("properties").at(propertyName)
        val (resolvedPropertySchema, resolvedPropertyContext) = resolveSchema(propertySchema, propertyContext)
        val optional = parameter.required != true || propertyName !in requiredProperties

        QueryParameterPatternEntry(
            key = toSpecmaticParamName(optional, propertyName),
            wireKey = propertyName,
            pattern = toQueryParameterPattern(
                propertyName,
                propertySchema,
                resolvedPropertySchema,
                resolvedPropertyContext,
                propertyContext
            ),
            source = QueryParameterPatternSource(parameter.name, propertyName),
            collectorContext = parameterContext,
            pointer = parameterPointer?.let { "$it/properties/${escapeJsonPointer(propertyName)}" }
        )
    }
}
