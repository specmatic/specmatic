package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.ObjectQueryRoot
import io.specmatic.core.QueryParameterSourceKind
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.QueryParameter

internal fun OpenApiSpecification.nestedObjectRootPropertyEntries(
    parameter: QueryParameter,
    resolvedSchema: Schema<*>,
    schemaContext: CollectorContext,
    nestedObjectQueryParam: NestedObjectQueryParam,
    schemaPointer: String?
): List<QueryParameterPatternEntry> {
    if (nestedObjectQueryParam.syntax.root != ObjectQueryRoot.Unwrapped) return emptyList()

    val requiredProperties = resolvedSchema.required.orEmpty().toSet()

    return resolvedSchema.properties.orEmpty().map { (propertyName, propertySchema) ->
        val propertyContext = schemaContext.at("properties").at(propertyName)
        val (resolvedPropertySchema, resolvedPropertyContext) = resolveSchemaIfRefElseAtSchema(
            schema = propertySchema,
            collectorContext = propertyContext
        )
        val optional = parameter.required != true || propertyName !in requiredProperties

        QueryParameterPatternEntry(
            key = toSpecmaticParamName(optional, propertyName),
            wireKey = propertyName,
            pattern = toQueryParameterPattern(
                parameterName = propertyName,
                schema = propertySchema,
                resolvedSchema = resolvedPropertySchema,
                resolvedSchemaContext = resolvedPropertyContext,
                schemaContext = propertyContext
            ),
            source = QueryParameterPatternSource(
                parameterName = parameter.name,
                propertyName = propertyName,
                kind = QueryParameterSourceKind.NestedObjectProperty
            ),
            collectorContext = propertyContext,
            pointer = schemaPointer?.let { "$it/properties/${escapeJsonPointer(propertyName)}" }
        )
    }
}
