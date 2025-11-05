package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.Row
import io.swagger.v3.oas.models.parameters.Parameter

class NoSecurityScheme : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        return Result.Success()
    }

    override fun equals(other: Any?): Boolean {
        return other is NoSecurityScheme
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        return httpRequest
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return requestPattern
    }

    override fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest {
        return newHttpRequest
    }

    override fun isInRow(row: Row): Boolean {
        return false
    }

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return false
    }

    override fun warnIfExistsInParameters(parameters: List<Parameter>, method: String, path: String) {
        return
    }

    override fun getParameterLocationIfPartOfSelf(parameter: String): List<SecuritySchemaParameterLocation> {
        return emptyList()
    }
}
