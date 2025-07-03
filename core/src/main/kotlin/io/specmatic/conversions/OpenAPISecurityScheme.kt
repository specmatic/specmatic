package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import io.swagger.v3.oas.models.parameters.Parameter

interface OpenAPISecurityScheme {
    fun matches(httpRequest: HttpRequest, resolver: Resolver): Result
    fun removeParam(httpRequest: HttpRequest): HttpRequest
    fun addTo(httpRequest: HttpRequest, resolver: Resolver = Resolver()): HttpRequest
    fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern
    fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest
    fun isInRow(row: Row): Boolean
    fun isInRequest(request: HttpRequest, complete: Boolean): Boolean
    fun getHeaderKey(): String? = null
    fun warnIfExistsInParameters(parameters: List<Parameter>, method: String, path: String)
}

fun addToHeaderType(
    headerName: String,
    row: Row,
    requestPattern: HttpRequestPattern
): HttpRequestPattern {
    val headerValueType = row.getField(headerName).let {
        if (isPatternToken(it))
            parsedPattern(it)
        else
            ExactValuePattern(StringValue(string = it))
    }

    return requestPattern.copy(
        headersPattern = requestPattern.headersPattern.copy(
            pattern = requestPattern.headersPattern.pattern.plus(headerName to headerValueType)
        )
    )
}

internal fun headerPatternFromRequest(
    request: HttpRequest,
    headerName: String
): Map<String, Pattern> {
    val headerValue = request.headers[headerName]

    if (headerValue != null) {
        return mapOf(headerName to ExactValuePattern(StringValue(headerValue)))
    }

    return emptyMap()
}

internal fun queryPatternFromRequest(
    request: HttpRequest,
    queryParamName: String
): Map<String, Pattern> {
    val queryParamValue = request.queryParams.getValues(queryParamName)

    if(queryParamValue.isEmpty())
        return emptyMap()

    return mapOf(
        queryParamName to ExactValuePattern(StringValue(queryParamValue.first()))
    )
}

internal fun printWarningsForOverriddenSecurityParameters(
    matchingParameters: List<Parameter>,
    securitySchemeDescription: String,
    httpParameterType: String,
    method: String,
    path: String
) {
    val parameterNames = matchingParameters.joinToString(", ") { it.name }
    val message =
        "Security scheme $securitySchemeDescription is defined in the OpenAPI specification, but conflicting $httpParameterType parameter(s) have been defined in the $method operation for path '$path'. This may lead to confusion or conflicts."
    println("Warning: $message")
    println("Conflicting $httpParameterType parameter(s): $parameterNames")
}
