package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern

data class APIKeyInHeaderSecurityScheme(val name: String, private val apiKey:String?) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest): Result {
        return if (httpRequest.headers.containsKey(name)) Result.Success() else Result.Failure("API-key named $name was not present as a header")
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(headers = httpRequest.headers.minus(name))
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        val headerValue = apiKey ?: resolver.generate("HEADERS", name, StringPattern()).toStringLiteral()
        return httpRequest.addSecurityHeader(name, headerValue)
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return addToHeaderType(name, row, requestPattern)
    }

    override fun isInRow(row: Row): Boolean = row.containsField(name)
}
