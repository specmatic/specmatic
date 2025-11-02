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
import io.specmatic.core.pattern.breadCrumb
import io.specmatic.core.pattern.isOptional
import io.specmatic.core.pattern.listFold
import io.specmatic.core.pattern.mapFold
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.pattern.withOptionality
import io.specmatic.core.pattern.withoutOptionality
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.links.Link

data class OpenApiOperationReference(val path: String, val method: String, val status: Int, val operationId: String?) {
    val convertedPath = convertPathParameterStyle(path)

    fun matches(path: String, method: String, status: Int): Boolean {
        return this.convertedPath == convertPathParameterStyle(path) && this.method.equals(method, ignoreCase = true) && this.status == status
    }

    fun matches(operationId: String?, status: Int): Boolean {
        if (this.operationId == null || operationId == null) return false
        return operationId == this.operationId && status == this.status
    }

    fun operationIdOrPathAndMethod(): String = operationId ?: "$convertedPath-$method"
}

data class OpenApiLink(
    val name: String,
    val isPartial: Boolean = false,
    val description: String? = null,
    val server: OpenApiServerObject? = null,
    val byOperation: OpenApiOperationReference,
    val forOperation: OpenApiOperationReference,
    val requestBody: OpenApiValueOrLinkExpression? = null,
    val parameters: Map<String, OpenApiValueOrLinkExpression> = emptyMap(),
) {
    fun matchesFor(operationId: String?, status: Int): Boolean = forOperation.matches(operationId, status)

    fun matchesFor(path: String, method: String, status: Int): Boolean = forOperation.matches(path, method, status)

    fun matchesBy(path: String, method: String, status: Int): Boolean = byOperation.matches(path, method, status)

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
            HasFailure("Expected mandatory path parameter '${param.key}' is missing from link parameters", param.key)
        }.listFold().unwrapOrReturn {
            return it.breadCrumb("LINKS.$name.parameters").cast()
        }

        return runCatching {
            val updatedDictionary = scenario.resolver.dictionary.addParameters(pathDictionary, BreadCrumb.PATH)
            val path = pathPattern.generate(resolver = scenario.resolver.copy(dictionary = updatedDictionary))
            httpRequest.copy(path = path)
        }.map(::HasValue).getOrElse(::HasException).breadCrumb("LINKS.$name.parameters")
    }

    private fun <T> Map<String, T>.containsPatternKey(key: String): Boolean {
        if (key in this) return true
        return withOptionality(key) in this
    }

    companion object {
        const val LINK_EXPECTED_STATUS_CODE = "x-StatusCode"
        const val LINK_PARTIAL = "x-Partial"

        fun from(parsedOpenApi: OpenAPI, byOperation: OpenApiOperationReference, name: String, parsedOpenApiLink: Link): ReturnValue<OpenApiLink> {
            val operationRefWithOperationFromOperationRef = parsedOpenApiLink.operationRef?.let {
                parsedOpenApi.verifyOperationReference(it)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.operationRef").cast()
            }

            val operationRefWithOperationFromOperationId = parsedOpenApiLink.operationId?.let {
                parsedOpenApi.verifyOperationId(it)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.operationId").cast()
            }

            if (operationRefWithOperationFromOperationRef == null && operationRefWithOperationFromOperationId == null) {
                return HasFailure("Must define either operationId or operationRef in OpenApi Link", "LINKS.$name")
            }

            val parameters = parsedOpenApiLink.parameters?.let {
                extractParameters(it, name)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.parameters").cast()
            }

            val requestBody = parsedOpenApiLink.requestBody?.let {
                OpenApiValueOrLinkExpression.from(it, name)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.requestBody").cast()
            }

            val server = parsedOpenApiLink.server?.let {
                OpenApiServerObject.from(it)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name").cast()
            }

            val operation = (operationRefWithOperationFromOperationId?.second ?: operationRefWithOperationFromOperationRef?.second) as Operation
            val statusCodeFromExtension: Int? = parsedOpenApiLink.extensions?.get(LINK_EXPECTED_STATUS_CODE)?.let {
                extractAndValidateExpectedStatusCode(it, operation)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.extensions.$LINK_EXPECTED_STATUS_CODE").cast()
            }

            val isPartialFromExtension: Boolean? = parsedOpenApiLink.extensions?.get(LINK_PARTIAL)?.let {
                extractAndValidateIsPartial(it)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.extensions.$LINK_PARTIAL").cast()
            }

            val firstStatusCode = operation.responses?.let { responses ->
                val first2xxStatusCode = responses.entries.firstOrNull { it.key.startsWith("2") }
                if (first2xxStatusCode != null) return@let first2xxStatusCode.key.toInt()
                DEFAULT_RESPONSE_CODE.takeIf { responses.contains("default") } ?: responses.entries.firstOrNull()?.key?.toIntOrNull()
            }

            val forStatusCode = statusCodeFromExtension ?: firstStatusCode ?: DEFAULT_RESPONSE_CODE
            val forOperation = (operationRefWithOperationFromOperationId?.first ?: operationRefWithOperationFromOperationRef?.first)
            return HasValue(
                OpenApiLink(
                    name = name,
                    server = server,
                    requestBody = requestBody,
                    parameters = parameters.orEmpty(),
                    byOperation = byOperation,
                    description = parsedOpenApiLink.description,
                    isPartial = isPartialFromExtension ?: false,
                    forOperation = (forOperation as OpenApiOperationReference).copy(status = forStatusCode),
                ),
            )
        }

        private fun OpenAPI.verifyOperationReference(operationRef: String): ReturnValue<Pair<OpenApiOperationReference, Operation>> {
            val (path, method) = parseAndExtractOperationReference(operationRef).unwrapOrReturn { return it.cast() }
            val pathItem = paths?.get(path) ?: return HasFailure("Path '$path' doesn't exist in the specification")
            val operation = pathItem.readOperationsMap()[method] ?: return HasFailure("Http Method '$method' doesn't exist on path '$path'")
            return HasValue(OpenApiOperationReference(path, method.name, DEFAULT_RESPONSE_CODE, operation.operationId) to operation)
        }

        private fun OpenAPI.verifyOperationId(operationId: String): ReturnValue<Pair<OpenApiOperationReference, Operation>> {
            paths.orEmpty().forEach { (path, pathItem) ->
                pathItem.readOperationsMap().map { (method, operation) ->
                    if (operation.operationId == operationId) {
                        val opRef = OpenApiOperationReference(path, method.name, DEFAULT_RESPONSE_CODE, operationId)
                        return HasValue(opRef to operation)
                    }
                }
            }
            return HasFailure("No Operation Found with operationId '$operationId' in the specification")
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

        private fun extractAndValidateExpectedStatusCode(value: Any, operation: Operation): ReturnValue<Int> {
            val statusCode = if (value is String && value.equals("default", ignoreCase = true)) {
                "default"
            } else {
                runCatching { value.toString().toInt().toString() }.getOrElse { _ ->
                    return HasFailure("Invalid Expected Status Code '$value' must be a valid integer or 'default'")
                }
            }

            val allPossibleStatusCodes = operation.responses.map { it.key.lowercase() }.toSet()
            return when {
                statusCode in allPossibleStatusCodes -> HasValue(statusCode.toIntOrNull() ?: DEFAULT_RESPONSE_CODE)
                "default" in allPossibleStatusCodes -> HasValue(DEFAULT_RESPONSE_CODE)
                else -> HasFailure("""
                Invalid status Code for $LINK_EXPECTED_STATUS_CODE '$statusCode' is not possible
                Must be one of ${allPossibleStatusCodes.joinToString(separator = ", ")}
                """.trimIndent())
            }
        }

        private fun extractAndValidateIsPartial(value: Any): ReturnValue<Boolean> {
            if (value is Boolean) return HasValue(value)
            if (value is String && value.lowercase() in setOf("true", "false")) return HasValue(value.toBoolean())
            return HasFailure("Invalid Is-Partial value '$value' must be a valid boolean")
        }
    }
}
