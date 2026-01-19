package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.*
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.Parameter
import org.apache.http.HttpHeaders.AUTHORIZATION

data class BearerSecurityScheme(private val configuredToken: String? = null) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        val authHeaderValue = httpRequest.headers.entries.find {
            it.key.equals(AUTHORIZATION, ignoreCase = true)
        } ?: return when(resolver.mockMode) {
            true -> Result.Success()
            else -> Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = resolver.mismatchMessages.expectedKeyWasMissing("header", AUTHORIZATION),
                ruleViolation = StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }

        if (!authHeaderValue.value.lowercase().startsWith("bearer")) {
            return Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = "$AUTHORIZATION header must be prefixed with \"Bearer\"",
                ruleViolation = StandardRuleViolation.TYPE_MISMATCH
            )
        }

        return Result.Success()
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        val headersWithoutAuthorization = httpRequest.headers.filterKeys { !it.equals(AUTHORIZATION, ignoreCase = true) }
        return httpRequest.copy(headers = headersWithoutAuthorization)
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        return httpRequest.addSecurityHeader(AUTHORIZATION, getAuthorizationHeaderValue(resolver))
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return addToHeaderType(AUTHORIZATION, row, requestPattern)
    }

    override fun isInRow(row: Row): Boolean {
        return row.columnNames.any { it.equals(AUTHORIZATION, ignoreCase = true) }
    }

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return request.hasHeader(AUTHORIZATION)
    }

    private fun getAuthorizationHeaderValue(resolver: Resolver): String {
        val updatedResolver = resolver.updateLookupForParam(BreadCrumb.HEADER.value)
        return "Bearer " + (configuredToken ?: updatedResolver.generate(null, AUTHORIZATION, StringPattern()).toStringLiteral())
    }

    override fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest {
        if (!originalRequest.headers.containsKey(AUTHORIZATION)) return newHttpRequest
        return newHttpRequest.addSecurityHeader(AUTHORIZATION, originalRequest.headers.getValue(AUTHORIZATION))
    }

    override fun getHeaderKey(): String? {
        return AUTHORIZATION
    }

    override fun collectErrorIfExistsInParameters(parameter: List<IndexedValue<Parameter>>, collectorContext: CollectorContext) {
        parameter.filter { indexedValue -> indexedValue.value is HeaderParameter }.forEach { (index, value) ->
            val paramContext = collectorContext.at("parameters").at(index)
            paramContext.check(name = "name", value = value, isValid = { !it.name.equals(AUTHORIZATION, ignoreCase = true) })
                .violation { OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED }
                .message { "Found header parameter with same name as Bearer Authorization security scheme" }
                .orUse { value }
                .build(isWarning = true)
        }
    }
}
