package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedQuerySchema
import io.swagger.v3.oas.models.media.Schema

internal fun Schema<*>.toNestedQuerySchema(
    collectorContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    visitedRefs: Set<String> = emptySet()
): NestedQuerySchema? {
    val ref = `$ref`
    if (ref != null) {
        return nestedQuerySchemaFromReference(ref, collectorContext, resolveSchemaReference, visitedRefs)
    }

    if (hasComposedSchema()) {
        val message = "Composed object query schemas are not supported"
        collectorContext.record(message, ruleViolation = OpenApiLintViolations.UNSUPPORTED_NESTED_QUERY_PARAMETER_SCHEMA)
        return NestedQuerySchema.Ambiguous(message)
    }

    return when {
        isSchema(OBJECT_TYPE) -> toNestedQueryObjectSchema(collectorContext, resolveSchemaReference, visitedRefs)
        isSchema(ARRAY_TYPE) -> toNestedQueryArraySchema(collectorContext, resolveSchemaReference, visitedRefs)
        else -> NestedQuerySchema.Scalar
    }
}

private fun nestedQuerySchemaFromReference(
    ref: String,
    collectorContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    visitedRefs: Set<String>
): NestedQuerySchema? {
    if (ref in visitedRefs) return null

    val resolvedSchema = resolveSchemaReference(ref, collectorContext)
    return resolvedSchema.toNestedQuerySchema(collectorContext, resolveSchemaReference, visitedRefs + ref)
}

private fun Schema<*>.toNestedQueryObjectSchema(
    collectorContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    visitedRefs: Set<String>
): NestedQuerySchema.Object {
    return NestedQuerySchema.Object(
        properties = nestedQueryProperties(collectorContext, resolveSchemaReference, visitedRefs),
        additionalProperties = nestedQueryAdditionalProperties(collectorContext, resolveSchemaReference, visitedRefs),
        allowsAnyAdditionalProperties = allowsAnyAdditionalProperties()
    )
}

private fun Schema<*>.nestedQueryProperties(
    collectorContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    visitedRefs: Set<String>
): Map<String, NestedQuerySchema> {
    return properties.orEmpty().mapNotNull { (propertyName, propertySchema) ->
        val propertyNestedQuerySchema = propertySchema.toNestedQuerySchema(
            collectorContext = collectorContext.at("properties").at(propertyName),
            resolveSchemaReference = resolveSchemaReference,
            visitedRefs = visitedRefs
        ) ?: return@mapNotNull null

        propertyName to propertyNestedQuerySchema
    }.toMap()
}

private fun Schema<*>.toNestedQueryArraySchema(
    collectorContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    visitedRefs: Set<String>
): NestedQuerySchema? {
    val nestedItemSchema = (items
        ?: return collectorContext.at("items").unsupportedNestedQuerySchema("Array query schema does not define items"))
        .toNestedQuerySchema(
            collectorContext = collectorContext.at("items"),
            resolveSchemaReference = resolveSchemaReference,
            visitedRefs = visitedRefs
        ) ?: return null

    return NestedQuerySchema.Array(itemSchema = nestedItemSchema)
}

private fun CollectorContext.unsupportedNestedQuerySchema(message: String): NestedQuerySchema.Ambiguous {
    record(message, ruleViolation = OpenApiLintViolations.UNSUPPORTED_NESTED_QUERY_PARAMETER_SCHEMA)
    return NestedQuerySchema.Ambiguous(message)
}

private fun Schema<*>.allowsAnyAdditionalProperties(): Boolean {
    val additionalProperties = extractAdditionalProperties()
    return additionalProperties == true || (properties.isNullOrEmpty() && additionalProperties == null)
}

private fun Schema<*>.nestedQueryAdditionalProperties(
    collectorContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    visitedRefs: Set<String>
): NestedQuerySchema? {
    return when (val additionalProperties = extractAdditionalProperties()) {
        is Schema<*> -> additionalProperties.toNestedQuerySchema(collectorContext.at("additionalProperties"), resolveSchemaReference, visitedRefs)
        else -> null
    }
}

private fun Schema<*>.extractAdditionalProperties(): Any? {
    val additionalProperties = this.additionalProperties
    return if (additionalProperties is Schema<*> && additionalProperties.booleanSchemaValue != null) {
        additionalProperties.booleanSchemaValue
    } else {
        additionalProperties
    }
}

private fun Schema<*>.isSchema(type: String): Boolean {
    return type in effectiveTypes()
}

private fun Schema<*>.hasComposedSchema(): Boolean {
    return oneOf != null || anyOf != null || allOf != null
}

private fun Schema<*>.effectiveTypes(): List<String> {
    val declared = types ?: setOfNotNull(this.type)
    return declared.filterNot { it == NULL_TYPE }
}

private const val OBJECT_TYPE = "object"
private const val ARRAY_TYPE = "array"
private const val NULL_TYPE = "null"
