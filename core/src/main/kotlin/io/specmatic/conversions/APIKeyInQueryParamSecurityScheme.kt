package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue

data class APIKeyInQueryParamSecurityScheme(val name: String, private val apiKey:String?) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        return if (httpRequest.queryParams.containsKey(name) || resolver.mockMode) Result.Success()
        else Result.Failure(
            breadCrumb = BreadCrumb.QUERY.with(name),
            message = resolver.mismatchMessages.expectedKeyWasMissing("API-Key", name)
        )
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(queryParams = httpRequest.queryParams.minus(name))
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        val updatedResolver = resolver.updateLookupForParam(BreadCrumb.QUERY.value)
        val apiKeyValue = apiKey ?: updatedResolver.generate(null, name, StringPattern()).toStringLiteral()
        return httpRequest.copy(queryParams = httpRequest.queryParams.plus(name to apiKeyValue))
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        val queryParamValueType = row.getField(name).let {
            if (isPatternToken(it))
                parsedPattern(it)
            else
                ExactValuePattern(StringValue(string = it))
        }

        return requestPattern.copy(
            httpQueryParamPattern = requestPattern.httpQueryParamPattern.copy(
                 queryPatterns = requestPattern.httpQueryParamPattern.queryPatterns.plus(name to queryParamValueType)
            )
        )
    }

    override fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest {
        if (!originalRequest.queryParams.containsKey(name)) return newHttpRequest
        val apiKeyValue = originalRequest.queryParams.getValues(name).first()
        return newHttpRequest.copy(queryParams = newHttpRequest.queryParams.plus(name to apiKeyValue))
    }

    override fun isInRow(row: Row): Boolean = row.containsField(name)

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return request.hasQueryParam(name)
    }
}
