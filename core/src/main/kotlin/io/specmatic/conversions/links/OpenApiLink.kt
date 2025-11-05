package io.specmatic.conversions.links

import io.ktor.http.ContentType
import io.specmatic.conversions.SecuritySchemaParameterLocation
import io.specmatic.conversions.convertPathParameterStyle
import io.specmatic.conversions.links.OpenApiLink.Companion.findMatchingContentType
import io.specmatic.core.BreadCrumb
import io.specmatic.core.CONTENT_TYPE
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
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.responses.ApiResponse
import kotlin.collections.contains
import kotlin.collections.plus

data class OpenApiOperationReference(val path: String, val method: String, val status: Int, val operationId: String?, val contentType: String? = null) {
    val convertedPath = convertPathParameterStyle(path)
    val parsedContentType = contentType?.let(ContentType::parse)

    fun matches(path: String, method: String, status: Int, contentType: String?): Boolean {
        return this.convertedPath == convertPathParameterStyle(path) && this.method.equals(method, ignoreCase = true) && this.status == status && matchesContentType(contentType)
    }

    fun matches(operationId: String?, status: Int, contentType: String?): Boolean {
        if (this.operationId == null || operationId == null) return false
        return operationId == this.operationId && status == this.status && matchesContentType(contentType)
    }

    fun matchesContentType(contentType: String?): Boolean {
        if (parsedContentType == null) return true
        if (contentType == null) return false
        val parsedMatchingType = ContentType.parse(contentType)
        return parsedContentType.match(parsedMatchingType)
    }

    fun operationIdOrPathAndMethod(): String = operationId ?: "$convertedPath-$method"

    companion object {
        const val LINK_CONTENT_TYPE_BY = "x-ContentType-By"

        fun updateContentType(openApiOperationReference: OpenApiOperationReference, response: ApiResponse, link: Link): ReturnValue<OpenApiOperationReference> {
            val contentType = link.extensions?.get(LINK_CONTENT_TYPE_BY)?.toString()
            return updateContentType(openApiOperationReference, response.content, contentType)
        }

        fun updateContentType(openApiOperationReference: OpenApiOperationReference, content: Content, contentType: String?): ReturnValue<OpenApiOperationReference> {
            val contentType = contentType?.let {
                findMatchingContentType(content, openApiOperationReference.status, it)
            }?.unwrapOrReturn {
                return it.cast()
            }

            return HasValue(openApiOperationReference.copy(contentType = contentType))
        }
    }
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
    fun matchesFor(operationId: String?, status: Int, contentType: String?): Boolean = forOperation.matches(operationId, status, contentType)

    fun matchesFor(path: String, method: String, status: Int, contentType: String?): Boolean = forOperation.matches(path, method, status, contentType)

    fun matchesBy(path: String, method: String, status: Int, contentType: String?): Boolean = byOperation.matches(path, method, status, contentType)

    fun toHttpRequest(scenario: Scenario): ReturnValue<HttpRequest> {
        if (
            !matchesFor(scenario.operationMetadata?.operationId, scenario.status, scenario.requestContentType) &&
            !matchesFor(scenario.path, scenario.method, scenario.status, scenario.requestContentType)
        ) {
            return HasFailure("OpenApi Link $name isn't for scenario ${scenario.defaultAPIDescription}", name)
        }

        val contentTypeHeaders = if (scenario.requestContentType != null) {
            mapOf(CONTENT_TYPE to scenario.requestContentType.orEmpty())
        } else {
            emptyMap()
        }

        val baseRequest = HttpRequest(method = scenario.method, body = requestBody?.value ?: NoBodyValue, headers = contentTypeHeaders)
        val (queryAndHeaderRequest, pathParams) = updateHeaderAndQueryParams(baseRequest, scenario)
        return updatePath(pathParams, queryAndHeaderRequest, scenario)
    }

    private fun updateHeaderAndQueryParams(httpRequest: HttpRequest, scenario: Scenario): Pair<HttpRequest, Map<String, OpenApiValueOrLinkExpression>> {
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
                    val updatedRequest = updateImplicitParams(key, expressionOrValue, request, scenario)
                    updatedRequest ?: run {
                        pathParameters[key] = expressionOrValue
                        request
                    }
                }
            }
        } to pathParameters
    }

    private fun updateImplicitParams(key: String, value: OpenApiValueOrLinkExpression, request: HttpRequest, scenario: Scenario): HttpRequest? {
        if (scenario.httpRequestPattern.headersPattern.pattern.containsPatternKey(key)) {
            return request.copy(headers = request.headers.plus(key to value.toStringLiteral()))
        }

        if (scenario.httpRequestPattern.httpQueryParamPattern.queryPatterns.containsPatternKey(key)) {
            return request.copy(queryParams = request.queryParams.plus(key to value.toStringLiteral()))
        }

        val securitySchemaLocation = scenario.httpRequestPattern.securitySchemes.firstNotNullOfOrNull {
            it.getParameterLocationIfPartOfSelf(key).takeUnless(List<SecuritySchemaParameterLocation>::isEmpty)
        }
        if (securitySchemaLocation != null) {
            return when (securitySchemaLocation.first()) {
                SecuritySchemaParameterLocation.HEADER -> request.copy(headers = request.headers.plus(key to value.toStringLiteral()))
                SecuritySchemaParameterLocation.QUERY -> request.copy(queryParams = request.queryParams.plus(key to value.toStringLiteral()))
            }
        }

        return null
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
        const val LINK_CONTENT_TYPE_FOR = "x-ContentType-For"
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
            val expectedStatusCodeToApiResponse = parsedOpenApiLink.extensions?.get(LINK_EXPECTED_STATUS_CODE)?.let {
                extractAndValidateExpectedStatusCode(it, operation)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.extensions.$LINK_EXPECTED_STATUS_CODE").cast()
            }

            val isPartialFromExtension: Boolean? = parsedOpenApiLink.extensions?.get(LINK_PARTIAL)?.let {
                extractAndValidateIsPartial(it)
            }?.unwrapOrReturn {
                return it.breadCrumb("LINKS.$name.extensions.$LINK_PARTIAL").cast()
            }

            val (firstStatusCode, firstApiResponse, contentType) = extractFirstRespInfo(operation).unwrapOrReturn {
                return it.breadCrumb("LINKS.$name").cast()
            }

            val forStatusCode = expectedStatusCodeToApiResponse?.first ?: firstStatusCode
            val forOperation = (operationRefWithOperationFromOperationId?.first ?: operationRefWithOperationFromOperationRef?.first)
            val updatedForOperation = OpenApiOperationReference.updateContentType(
                openApiOperationReference = (forOperation as OpenApiOperationReference).copy(status = forStatusCode),
                content = operation.requestBody?.content ?: Content(),
                contentType = parsedOpenApiLink.extensions?.get(LINK_CONTENT_TYPE_FOR)?.toString() ?: contentType,
            ).unwrapOrReturn {
                return if (parsedOpenApiLink.extensions?.get(LINK_CONTENT_TYPE_FOR) != null) {
                    it.breadCrumb("LINKS.$name.$LINK_CONTENT_TYPE_FOR").cast()
                } else {
                    it.breadCrumb("LINKS.$name").cast()
                }
            }

            return HasValue(
                OpenApiLink(
                    name = name,
                    server = server,
                    requestBody = requestBody,
                    parameters = parameters.orEmpty(),
                    byOperation = byOperation,
                    description = parsedOpenApiLink.description,
                    isPartial = isPartialFromExtension ?: false,
                    forOperation = updatedForOperation,
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

        private fun extractAndValidateExpectedStatusCode(value: Any, operation: Operation): ReturnValue<Pair<Int, ApiResponse>> {
            val statusCode = if (value is String && value.equals("default", ignoreCase = true)) {
                "default"
            } else {
                runCatching { value.toString().toInt().toString() }.getOrElse { _ ->
                    return HasFailure("Invalid Expected Status Code '$value' must be a valid integer or 'default'")
                }
            }

            val allPossibleStatusCodes = operation.responses.orEmpty().map { it.key.lowercase() }.toSet()
            return when {
                statusCode in allPossibleStatusCodes -> {
                    val apiResponse = operation.responses.getValue(statusCode)
                    val status = statusCode.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
                    HasValue(status to apiResponse)
                }
                "default" in allPossibleStatusCodes -> {
                    val apiResponse = operation.responses.getValue("default")
                    HasValue(DEFAULT_RESPONSE_CODE to apiResponse)
                }
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

        private fun extractFirstRespInfo(operation: Operation): ReturnValue<Triple<Int, ApiResponse, String?>> {
            val firstOperation = sequenceOf(
                operation.responses?.entries?.firstOrNull { it.key.startsWith("2") },
                operation.responses?.entries?.firstOrNull { it.key.equals("default", ignoreCase = true) },
                operation.responses?.entries?.firstOrNull(),
            ).firstNotNullOfOrNull { it }

            if (firstOperation == null) return HasFailure("""
            Failed to pick and default expected response from operation
            """.trimIndent())

            val statusCode = firstOperation.key.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
            val firstContentType = operation.requestBody?.content?.keys?.firstOrNull()
            return HasValue(Triple(statusCode, firstOperation.value, firstContentType))
        }

        fun findMatchingContentType(content: Content, statusCode: Any, contentType: String): ReturnValue<String> {
            val expectedParsedContentType = ContentType.parse(contentType)
            val matchingContentType = content?.asSequence()?.filter {
                val thisContentType = ContentType.parse(it.key)
                expectedParsedContentType.match(thisContentType)
            }?.firstOrNull()

            return if (matchingContentType == null) {
                HasFailure("""
                Expected Content-Type of $contentType is not possible under $statusCode
                Must match one of ${content?.keys?.joinToString(separator = ", ") ?: "<EMPTY>"}
                """.trimIndent())
            } else {
                HasValue(matchingContentType.key)
            }
        }
    }
}
