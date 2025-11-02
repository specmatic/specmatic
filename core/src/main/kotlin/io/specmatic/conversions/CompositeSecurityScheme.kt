package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.Row
import io.swagger.v3.oas.models.parameters.Parameter

data class CompositeSecurityScheme(val schemes: List<OpenAPISecurityScheme>): OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        val results = schemes.map { it.matches(httpRequest, resolver) }
        return Result.fromResults(results)
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return schemes.fold(httpRequest) { request, scheme ->
            scheme.removeParam(request)
        }
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        return schemes.fold(httpRequest) { request, scheme ->
            scheme.addTo(request, resolver)
        }
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return schemes.fold(requestPattern) { pattern, scheme ->
            scheme.addTo(pattern, row)
        }
    }

    override fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest {
        return schemes.fold(newHttpRequest) { request, scheme ->
            scheme.copyFromTo(originalRequest, request)
        }
    }

    override fun isInRow(row: Row): Boolean {
        return schemes.all { it.isInRow(row) }
    }

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return if (!complete) schemes.any { it.isInRequest(request, false) }
        else schemes.all { it.isInRequest(request, true) }
    }

    override fun warnIfExistsInParameters(parameters: List<Parameter>, method: String, path: String) {
        schemes.forEach { securityScheme ->
            securityScheme.warnIfExistsInParameters(parameters, method, path)
        }
    }

    override fun getParameterLocationIfPartOfSelf(parameter: String): List<SecuritySchemaParameterLocation> {
        return schemes.flatMap { getParameterLocationIfPartOfSelf(parameter) }
    }
}
