package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.log.logger
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

internal fun printWarningsForOverriddenSecurityParameters(
    matchingParameters: List<Parameter>,
    securitySchemeDescription: String,
    httpParameterType: String,
    method: String,
    path: String
) {
    val parameterNames = matchingParameters.joinToString(", ") { it.name }
    val warningMsg = warningsForOverriddenSecurityParameters(
        matchingParameters = parameterNames,
        securitySchemeDescription = securitySchemeDescription,
        httpParameterType = httpParameterType,
        method = method,
        path = path
    )
    logger.log(warningMsg)
    logger.boundary()
}

internal fun warningsForOverriddenSecurityParameters(
    matchingParameters: String,
    securitySchemeDescription: String,
    httpParameterType: String,
    method: String,
    path: String
): Warning {
    return Warning(
        problem = "Security scheme $securitySchemeDescription is defined in the OpenAPI specification, but conflicting $httpParameterType parameter(s) $matchingParameters have been defined in the $method operation for path '$path'.",
        implications = "This may lead to confusion or conflicts.",
        resolution = "Consider removing the conflicting $httpParameterType parameter(s) or updating the security scheme definition to avoid conflicts."
    )
}