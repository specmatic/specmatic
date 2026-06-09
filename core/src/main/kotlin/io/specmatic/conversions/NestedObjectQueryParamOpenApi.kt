package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.NestedObjectQuerySyntaxInference
import io.specmatic.core.NestedQueryParameterExamples
import io.specmatic.core.NestedQuerySchema
import io.specmatic.core.NestedQuerySyntaxInferenceResult
import io.specmatic.core.ObjectQueryRoot
import io.specmatic.core.ObjectQuerySyntax
import io.specmatic.core.QueryArrayIndexStyle
import io.specmatic.core.QueryPropertyStyle
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

    return when (val inferenceResult = NestedObjectQuerySyntaxInference.infer(parameter.name, nestedQuerySchema, parameterExamples)) {
        is NestedQuerySyntaxInferenceResult.SyntaxInferred -> NestedObjectQueryParam(
            parameterName = parameter.name,
            required = parameter.required == true,
            schema = nestedQuerySchema,
            syntax = inferenceResult.syntax
        )
        is NestedQuerySyntaxInferenceResult.SyntaxNotRequired -> null
        is NestedQuerySyntaxInferenceResult.Failure -> NestedObjectQueryParam(
            parameterName = parameter.name,
            required = parameter.required == true,
            schema = nestedQuerySchema,
            syntax = defaultNestedObjectQuerySyntax()
        )
    }
}

private fun defaultNestedObjectQuerySyntax(): ObjectQuerySyntax {
    return ObjectQuerySyntax(ObjectQueryRoot.Unwrapped, QueryPropertyStyle.Dot, QueryArrayIndexStyle.Bracket)
}

internal fun nestedObjectQueryStringExampleEntries(
    parameter: QueryParameter,
    exampleValue: Any,
    nestedObjectQueryParam: NestedObjectQueryParam?
): Map<String, Any>? {
    val exampleString = exampleValue as? String ?: return null
    val nestedQueryParam = nestedObjectQueryParam ?: return null

    return mapOf(parameter.name to nestedQueryParam.reconstructObjectValueFromQueryParamPairs(queryStringExampleEntries(exampleString)))
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
): NestedQuerySchema {
    if (`$ref` != null) {
        val ref = `$ref`
        if (ref in visitedRefs) return NestedQuerySchema.Ambiguous("Circular schema reference $ref")

        val resolvedSchema = resolveSchemaReference(ref, collectorContext)
        return resolvedSchema.toNestedQuerySchema(collectorContext, resolveSchemaReference, visitedRefs + ref)
    }

    if (oneOf != null || anyOf != null || allOf != null) {
        return NestedQuerySchema.Ambiguous("Composed object query schemas are not supported")
    }

    return when {
        isSchema(OBJECT_TYPE) -> NestedQuerySchema.Object(
            properties = properties.orEmpty().mapValues { (propertyName, propertySchema) ->
                propertySchema.toNestedQuerySchema(
                    collectorContext = collectorContext.at("properties").at(propertyName),
                    resolveSchemaReference = resolveSchemaReference,
                    visitedRefs = visitedRefs
                )
            },
            additionalProperties = nestedQueryAdditionalProperties(collectorContext, resolveSchemaReference, visitedRefs),
            allowsAnyAdditionalProperties = extractAdditionalProperties() == true
        )
        isSchema(ARRAY_TYPE) -> NestedQuerySchema.Array(
            itemSchema = items?.toNestedQuerySchema(
                collectorContext = collectorContext.at("items"),
                resolveSchemaReference = resolveSchemaReference,
                visitedRefs = visitedRefs
            ) ?: NestedQuerySchema.Ambiguous("Array query schema does not define items")
        )
        else -> NestedQuerySchema.Scalar
    }
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
    return type in schemaMeta().effectiveTypes
}

private data class SchemaMeta(val effectiveTypes: List<String>)

private fun Schema<*>.schemaMeta(): SchemaMeta {
    val declared = types ?: setOfNotNull(this.type)
    return SchemaMeta(declared.filterNot { it == NULL_TYPE })
}

private const val OBJECT_TYPE = "object"
private const val ARRAY_TYPE = "array"
private const val NULL_TYPE = "null"
