package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue

interface OpenAPISecurityScheme {
    fun matches(httpRequest: HttpRequest, resolver: Resolver): Result
    fun removeParam(httpRequest: HttpRequest): HttpRequest
    fun addTo(httpRequest: HttpRequest, resolver: Resolver = Resolver()): HttpRequest
    fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern
    fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest
    fun isInRow(row: Row): Boolean
    fun isInRequest(request: HttpRequest, complete: Boolean): Boolean
    fun getHeaderKey(): String? = null
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
