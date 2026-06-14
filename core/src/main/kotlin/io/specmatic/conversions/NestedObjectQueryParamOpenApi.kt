package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.NestedObjectQuerySyntaxInference
import io.specmatic.core.NestedQueryParameterExamples
import io.specmatic.core.NestedQuerySchema
import io.specmatic.core.NestedQuerySyntaxInferenceResult
import io.specmatic.core.ObjectQuerySyntax
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.reconstructObjectValueFromQueryParamPairs
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.QueryParameter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun nestedObjectQueryParam(
    parameter: QueryParameter,
    resolvedSchema: Schema<*>,
    parameterContext: CollectorContext,
    resolveSchemaReference: (String, CollectorContext) -> Schema<*>,
    resolveExample: (Example?) -> Example?
): NestedObjectQueryParam? {
    val nestedQuerySchema = resolvedSchema.toNestedQuerySchema(
        collectorContext = parameterContext.at("schema"),
        resolveSchemaReference = resolveSchemaReference
    ) as? NestedQuerySchema.Object ?: return null

    val parameterExamples = parameter.nestedQueryExamples(resolveExample)
    val syntax = nestedObjectQuerySyntax(parameter.name, nestedQuerySchema, parameterExamples, parameterContext) ?: return null

    return NestedObjectQueryParam(
        parameterName = parameter.name,
        required = parameter.required == true,
        schema = nestedQuerySchema,
        syntax = syntax
    )
}

private fun nestedObjectQuerySyntax(
    parameterName: String,
    schema: NestedQuerySchema.Object,
    parameterExamples: NestedQueryParameterExamples,
    parameterContext: CollectorContext
): ObjectQuerySyntax? {
    return when (val inferenceResult = NestedObjectQuerySyntaxInference.infer(parameterName, schema, parameterExamples)) {
        is NestedQuerySyntaxInferenceResult.SyntaxInferred -> inferenceResult.syntax
        is NestedQuerySyntaxInferenceResult.SyntaxNotRequired -> null
        is NestedQuerySyntaxInferenceResult.Failure -> {
            recordInlineNestedQueryExampleFailures(parameterExamples, inferenceResult, parameterContext)
            ObjectQuerySyntax.Default
        }
    }
}

private fun recordInlineNestedQueryExampleFailures(
    parameterExamples: NestedQueryParameterExamples,
    inferenceResult: NestedQuerySyntaxInferenceResult.Failure,
    parameterContext: CollectorContext
) {
    if (!parameterExamples.hasOnlyInlineExample()) return

    inferenceResult.messages.forEach { message ->
        parameterContext.at("example").record(
            message = message,
            ruleViolation = OpenApiLintViolations.INVALID_NESTED_QUERY_PARAMETER_EXAMPLE
        )
    }
}

private fun NestedQueryParameterExamples.hasOnlyInlineExample(): Boolean {
    return example != null && examples.isEmpty()
}

internal fun nestedObjectQueryStringExampleEntries(
    parameter: QueryParameter,
    exampleValue: Any,
    nestedObjectQueryParam: NestedObjectQueryParam?,
    effectivePatterns: Map<String, Pattern>,
    resolver: Resolver,
    exampleContext: CollectorContext
): Map<String, Any>? {
    val exampleString = exampleValue as? String ?: return null
    val nestedQueryParam = nestedObjectQueryParam ?: return null

    return runCatching {
        mapOf(
            parameter.name to nestedQueryParam.reconstructObjectValueFromQueryParamPairs(
                pairs = queryStringExampleEntries(exampleString),
                effectivePatterns = effectivePatterns,
                resolver = resolver
            )
        )
    }.getOrElse { exception ->
        exampleContext.record(
            message = exception.message ?: "Invalid nested query parameter example",
            ruleViolation = nestedQueryExampleViolation(exception)
        )
        null
    }
}

private fun nestedQueryExampleViolation(exception: Throwable): OpenApiLintViolations {
    return when {
        exception is ContractException && exception.message.orEmpty().contains("Ambiguous query object schema") ->
            OpenApiLintViolations.UNSUPPORTED_NESTED_QUERY_PARAMETER_SCHEMA
        else -> OpenApiLintViolations.INVALID_NESTED_QUERY_PARAMETER_EXAMPLE
    }
}

private fun queryStringExampleEntries(exampleValue: String): List<Pair<String, String>> {
    return exampleValue.split("&")
        .filter(String::isNotBlank)
        .map { entry ->
            val key = entry.substringBefore("=")
            val value = entry.substringAfter("=", "")
            urlDecode(key) to urlDecode(value)
        }
}

private fun urlDecode(value: String): String {
    return URLDecoder.decode(value, StandardCharsets.UTF_8)
}

private fun QueryParameter.nestedQueryExamples(resolveExample: (Example?) -> Example?): NestedQueryParameterExamples {
    return NestedQueryParameterExamples(
        example = example?.toString(),
        examples = examples.orEmpty().values.mapNotNull { example ->
            resolveExample(example)?.value?.toString()
        }
    )
}

private fun Schema<*>.toNestedQuerySchema(
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
