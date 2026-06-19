package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.NestedObjectQueryParam
import io.specmatic.core.NestedQueryParameterExamples
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.parameters.QueryParameter
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

internal fun QueryParameter.nestedQueryExamples(resolveExample: (Example?) -> Example?): NestedQueryParameterExamples {
    return NestedQueryParameterExamples(
        example = example?.toString(),
        examples = examples.orEmpty().values.mapNotNull { example ->
            resolveExample(example)?.value?.toString()
        }
    )
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
