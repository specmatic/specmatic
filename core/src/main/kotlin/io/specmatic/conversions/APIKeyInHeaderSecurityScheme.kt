package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern


data class APIKeyInHeaderSecurityScheme(
    val name: String,
    private val apiKey: String?,
    override val schemeName: String? = null,
) : OpenAPISecurityScheme {
    override fun failIfInRequest(httpRequest: HttpRequest, resolver: Resolver): Result {
        val entry = httpRequest.headers.getCaseInsensitive(name) ?: return Result.Success()
        return Result.Failure(
            breadCrumb = BreadCrumb.HEADER.with(entry.key),
            message = resolver.mismatchMessages.unexpectedKey("header", entry.key)
        )
    }

    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        return if (httpRequest.hasHeader(name) || resolver.mockMode) Result.Success()
        else Result.Failure(
            breadCrumb = BreadCrumb.HEADER.with(name),
            message = resolver.mismatchMessages.expectedKeyWasMissing(apiKeyParamName, name),
            ruleViolation = StandardRuleViolation.REQUIRED_PROPERTY_MISSING
        )
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(headers = httpRequest.headers.minusCaseInsensitive(name))
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        val updatedResolver = resolver.updateLookupForParam(BreadCrumb.HEADER.value)
        val headerValue = apiKey ?: updatedResolver.generate(null, name, StringPattern()).toStringLiteral()
        return httpRequest.addSecurityHeader(name, headerValue)
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return addToHeaderType(name, row, requestPattern)
    }

    override fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest {
        val headerValue = originalRequest.headers.getCaseInsensitive(name)?.value ?: return newHttpRequest
        return newHttpRequest.addSecurityHeader(name, headerValue)
    }

    override fun isInRow(row: Row): Boolean = row.containsField(name)

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return request.hasHeader(name)
    }

    override fun getHeaderKey(): String? {
        return apiKeyParamName
    }
}
