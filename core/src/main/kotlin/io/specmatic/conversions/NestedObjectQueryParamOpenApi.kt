package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.NestedObjectQuerySyntaxInference
import io.specmatic.core.NestedQueryParameterExamples
import io.specmatic.core.NestedQuerySchema
import io.specmatic.core.NestedQuerySyntaxInferenceResult
import io.specmatic.core.ObjectQuerySyntax
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.QueryParameter

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
