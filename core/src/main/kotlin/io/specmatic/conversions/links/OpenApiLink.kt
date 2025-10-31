package io.specmatic.conversions.links

import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.core.BreadCrumb
import io.specmatic.core.DEFAULT_RESPONSE_CODE
import io.specmatic.core.HttpRequest
import io.specmatic.core.NoBodyValue
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.isOptional
import io.specmatic.core.pattern.listFold
import io.specmatic.core.pattern.mapFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.pattern.withOptionality
import io.specmatic.core.pattern.withoutOptionality
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.links.Link

data class OpenApiOperationReference(val path: String, val method: String, val status: Int) {
    fun matches(path: String, method: String, status: Int): Boolean {
        return this.path == convertPathParameterStyle(path) && this.method.equals(method, ignoreCase = true) && this.status == status
    }
}

data class OpenApiLink(
    val name: String,
    val forStatusCode: Int,
    val isPartial: Boolean = false,
    val description: String? = null,
    val operationId: String? = null,
    val server: OpenApiServerObject? = null,
    val byOperationReference: OpenApiOperationReference,
    val requestBody: OpenApiValueOrLinkExpression? = null,
    val forOperationReference: OpenApiOperationReference? = null,
    val parameters: Map<String, OpenApiValueOrLinkExpression> = emptyMap(),
) {
    fun matchesFor(operationId: String?, status: Int): Boolean {
        if (this.operationId == null || operationId == null) return false
        return operationId == this.operationId && status == forStatusCode
    }

    fun matchesFor(path: String, method: String, status: Int): Boolean {
        if (this.operationId != null) return false
        if (this.forOperationReference == null) return false
        return this.forOperationReference.matches(path, method, status)
    }

    fun matchesBy(path: String, method: String, status: Int): Boolean {
        return this.byOperationReference.matches(path, method, status)
    }

    fun toHttpRequest(scenario: Scenario): ReturnValue<HttpRequest> {
        if (
            !matchesFor(scenario.operationMetadata?.operationId, scenario.status) &&
            !matchesFor(scenario.path, scenario.method, scenario.status)
        ) {
            return HasFailure("OpenApi Link $name isn't for scenario ${scenario.defaultAPIDescription}", name)
        }

        val baseRequest = HttpRequest(method = scenario.method, body = requestBody?.value ?: NoBodyValue)
        val (queryAndHeaderRequest, pathParams) = updateHeaderAndQueryParams(baseRequest, scenario)
        return updatePath(pathParams, queryAndHeaderRequest, scenario)
    }

    private fun updateHeaderAndQueryParams(httpRequest: HttpRequest, scenario: Scenario): Pair<HttpRequest, Map<String, OpenApiValueOrLinkExpression>> {
        // TODO: Handle query and header params that are also securitySchemes
        val pathParameters = mutableMapOf<String, OpenApiValueOrLinkExpression>()
        return parameters.entries.fold(httpRequest) { request, (key, expressionOrValue) ->
            when {
                key.startsWith("path.") -> request.also { pathParameters[key] = expressionOrValue }
                key.startsWith("header.") -> {
                    val headerName = key.removePrefix("header.")
                    request.copy(headers = request.headers.plus(headerName to expressionOrValue.toStringLiteral()))
                }
                key.startsWith("query.") -> {
                    val queryParamName = key.removePrefix("query.")
                    request.copy(queryParams = request.queryParams.plus(queryParamName to expressionOrValue.toStringLiteral()))
                }
                else -> {
                    if (scenario.httpRequestPattern.headersPattern.pattern.containsPatternKey(key)) {
                        request.copy(headers = request.headers.plus(key to expressionOrValue.toStringLiteral()))
                    } else if (scenario.httpRequestPattern.httpQueryParamPattern.queryPatterns.containsPatternKey(key)) {
                        request.copy(queryParams = request.queryParams.plus(key to expressionOrValue.toStringLiteral()))
                    } else {
                        pathParameters[key] = expressionOrValue
                        request
                    }
                }
            }
        } to pathParameters
    }

    private fun updatePath(pathParameters: Map<String, OpenApiValueOrLinkExpression>, httpRequest: HttpRequest, scenario: Scenario): ReturnValue<HttpRequest> {
        val pathPattern = scenario.httpRequestPattern.httpPathPattern ?: return HasValue(httpRequest)
        val pathDictionary = pathParameters.map { (key, exprOrValue) ->
            if (key.startsWith("path")) key.removePrefix("path.") to exprOrValue.value
            else key to exprOrValue.escapedIfString()
        }.toMap()

        pathPattern.pathParameters().map { param ->
            if (withoutOptionality(param.key.orEmpty()) in pathDictionary) return@map HasValue(Unit)
            if (isOptional(param.key.orEmpty())) return@map HasValue(Unit)
            HasFailure("Expected mandatory path parameter ${param.key} is missing from link parameters", name)
        }.listFold().unwrapOrReturn {
            return it.cast()
        }

        return runCatching {
            val updatedDictionary = scenario.resolver.dictionary.addParameters(pathDictionary, BreadCrumb.PATH)
            val path = pathPattern.generate(resolver = scenario.resolver.copy(dictionary = updatedDictionary))
            httpRequest.copy(path = path)
        }.map(::HasValue).getOrElse(::HasException)
    }

    private fun <T> Map<String, T>.containsPatternKey(key: String): Boolean {
        if (key in this) return true
        return withOptionality(key) in this
    }

    companion object {
        const val LINK_EXPECTED_STATUS_CODE = "x-StatusCode"
        const val LINK_PARTIAL = "x-Partial"

        fun from(parsedOpenApi: OpenAPI, byOperationReference: OpenApiOperationReference, name: String, parsedOpenApiLink: Link): ReturnValue<OpenApiLink> {
            val operationRefWithStatusCode = parsedOpenApiLink.operationRef?.let {
                parsedOpenApi.verifyOperationReference(it)
            }?.unwrapOrReturn {
                return it.cast()
            }

            val operationIdWithStatusCode = parsedOpenApiLink.operationId?.let {
                parsedOpenApi.verifyOperationId(it)
            }?.unwrapOrReturn {
                return it.cast()
            }

            if (operationRefWithStatusCode == null && operationIdWithStatusCode == null) {
                return HasFailure("Must define either operationId or operationRef in OpenApi Link")
            }

            val parameters = parsedOpenApiLink.parameters?.let {
                extractParameters(it, name)
            }?.unwrapOrReturn {
                return it.cast()
            }

            val requestBody = parsedOpenApiLink.requestBody?.let {
                OpenApiValueOrLinkExpression.from(it, name)
            }?.unwrapOrReturn {
                return it.cast()
            }

            val server = parsedOpenApiLink.server?.let {
                OpenApiServerObject.from(it)
            }?.unwrapOrReturn {
                return it.cast()
            }

            val statusCodeFromExtension: Int? = parsedOpenApiLink.extensions?.get(LINK_EXPECTED_STATUS_CODE)?.let {
                if (it is String && it.equals("default", ignoreCase = true)) return@let HasValue(DEFAULT_RESPONSE_CODE)
                runCatching { it.toString().toInt() }.map(::HasValue).getOrElse(::HasException).addDetails(
                    "Invalid Expected Status Code $it", name,
                )
            }?.unwrapOrReturn {
                return it.cast()
            }

            val isPartialFromExtension: Boolean? = parsedOpenApiLink.extensions?.get(LINK_PARTIAL)?.let {
                runCatching { it.toString().toBoolean() }.map(::HasValue).getOrElse(::HasException).addDetails(
                    "Invalid Partial Boolean value $it", name,
                )
            }?.unwrapOrReturn {
                return it.cast()
            }

            val firstStatusCode = operationIdWithStatusCode?.second ?: operationRefWithStatusCode?.second
            val forStatusCode = statusCodeFromExtension ?: firstStatusCode ?: DEFAULT_RESPONSE_CODE

            return HasValue(
                OpenApiLink(
                    name = name,
                    server = server,
                    requestBody = requestBody,
                    forStatusCode = forStatusCode,
                    parameters = parameters.orEmpty(),
                    byOperationReference = byOperationReference,
                    description = parsedOpenApiLink.description,
                    isPartial = isPartialFromExtension ?: false,
                    operationId = operationIdWithStatusCode?.first,
                    forOperationReference = operationRefWithStatusCode?.first?.copy(status = forStatusCode),
                ),
            )
        }

        private fun OpenAPI.verifyOperationReference(operationRef: String): ReturnValue<Pair<OpenApiOperationReference, Int>> {
            val (path, method) = parseAndExtractOperationReference(operationRef).unwrapOrReturn { return it.cast() }
            val pathItem = paths?.get(path) ?: return HasFailure("Path doesn't exist", path)
            val operation = pathItem.readOperationsMap()[method] ?: return HasFailure("Http Method $method, doesn't exist on path $path", method.name)
            val firstStatusCode = operation.responses?.entries?.firstOrNull()?.key?.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
            return HasValue(OpenApiOperationReference(path, method.name, firstStatusCode) to firstStatusCode)
        }

        private fun OpenAPI.verifyOperationId(operationId: String): ReturnValue<Pair<String, Int>> {
            paths.orEmpty().forEach { (_, pathItem) ->
                pathItem.readOperations().map { operation ->
                    if (operation.operationId == operationId) {
                        val firstStatusCode = operation.responses?.entries?.firstOrNull()?.key?.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
                        return HasValue(operationId to firstStatusCode)
                    }
                }
            }
            return HasFailure("No Operation Found with id $operationId", operationId)
        }

        private fun extractParameters(parameters: Map<String, String>, name: String): ReturnValue<Map<String, OpenApiValueOrLinkExpression>> {
            return parameters.mapValues { (key, value) ->
                OpenApiValueOrLinkExpression.from(value, name).addDetails("Failed to parse parameter $key", key)
            }.mapFold()
        }

        private fun parseAndExtractOperationReference(operationRef: String): ReturnValue<Pair<String, PathItem.HttpMethod>> {
            val parts = operationRef.split("#/paths/").filter(String::isNotEmpty)
            if (parts.size != 1) return HasFailure("Invalid operation reference", operationRef)

            val (encodedPath, method) = parts.single().split("/", limit = 2)
            val decodedPath = encodedPath.replace("~0", "~").replace("~1", "/")
            val upperCasedMethod = method.uppercase()

            return runCatching {
                Pair(decodedPath, PathItem.HttpMethod.valueOf(upperCasedMethod))
            }.map(::HasValue).getOrElse(::HasException)
        }
    }
}
