package io.specmatic.conversions

import io.specmatic.conversions.lenient.CollectorContext
import io.specmatic.core.*
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.Parameter


data class APIKeyInHeaderSecurityScheme(
    val name: String,
    private val apiKey: String?,
    private val schemeName: String = name
) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        return if (httpRequest.headers.containsKey(name) || resolver.mockMode) Result.Success()
        else Result.Failure(
            breadCrumb = BreadCrumb.HEADER.with(name),
            message = resolver.mismatchMessages.expectedKeyWasMissing(apiKeyParamName, name),
            ruleViolation = StandardRuleViolation.REQUIRED_PROPERTY_MISSING
        )
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(headers = httpRequest.headers.minus(name))
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
        if (!originalRequest.headers.containsKey(name)) return newHttpRequest
        return newHttpRequest.addSecurityHeader(name, originalRequest.headers.getValue(name))
    }

    override fun isInRow(row: Row): Boolean = row.containsField(name)

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return request.hasHeader(name)
    }

    override fun collectErrorIfExistsInParameters(parameter: List<IndexedValue<Parameter>>, collectorContext: CollectorContext) {
        parameter.filter { indexedValue -> indexedValue.value is HeaderParameter }.forEach { (index, value) ->
            val paramContext = collectorContext.at("parameters").at(index)
            paramContext.check(name = "name", value = value, isValid = { !it.name.equals(name, ignoreCase = true) })
                .violation { OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED }
                .message {
                    "The header/query param named \"$name\" for security scheme named \"$schemeName\" was explicitly re-defined as a parameter. The parameter will be ignored, and should be removed."
                }
                .orUse { value }
                .build(isWarning = true)
        }
    }

    override fun getHeaderKey(): String? {
        return apiKeyParamName
    }
}
