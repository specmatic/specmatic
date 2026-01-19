package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
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
    fun collectErrorIfExistsInParameters(parameter: List<IndexedValue<Parameter>>, collectorContext: CollectorContext)
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
