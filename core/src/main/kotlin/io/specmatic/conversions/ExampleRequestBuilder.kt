package io.specmatic.conversions

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.pattern.parsedValue

class ExampleRequestBuilder(
    examplePathParams: Map<String, Map<String, String>>,
    exampleHeaderParams: Map<String, Map<String, String>>,
    exampleQueryParams: Map<String, Map<String, String>>,
    val httpPathPattern: HttpPathPattern,
    private val httpMethod: String,
    val securitySchemes: List<OpenAPISecurityScheme>
) {
    fun examplesWithRequestBodies(exampleBodies: Map<String, String?>, contentType: String): Map<String, List<HttpRequest>> {
        val examplesWithBodies: Map<String, List<HttpRequest>> = exampleBodies.mapValues { (exampleName, bodyValue) ->
            val bodies: List<HttpRequest> = if(exampleName in examplesBasedOnParameters) {
                examplesBasedOnParameters.getValue(exampleName).map { exampleRequest ->
                    exampleRequest
                        .copy(
                            headers = exampleRequest.headers + if(CONTENT_TYPE !in exampleRequest.headers) mapOf("Content-Type" to contentType) else exampleRequest.headers,
                            body = parsedValue(bodyValue, contentType))
                }
            } else {
                val httpRequest = HttpRequest(
                    method = httpMethod,
                    path = httpPathPattern.path,
                    headers = mapOf("Content-Type" to contentType),
                    body = parsedValue(bodyValue, contentType)
                )

                val requestsWithSecurityParams = securitySchemes.map { securityScheme ->
                    securityScheme.addTo(httpRequest)
                }

                requestsWithSecurityParams
            }

            bodies
        }

        val examplesWithoutBodies = (examplesBasedOnParameters.keys - exampleBodies.keys).associateWith { key -> examplesBasedOnParameters.getValue(key) }

        val allExamples = examplesWithBodies + examplesWithoutBodies

        return allExamples
    }

    private val unionOfParameterKeys =
        (exampleQueryParams.keys + examplePathParams.keys + exampleHeaderParams.keys).distinct()

    val examplesBasedOnParameters: Map<String, List<HttpRequest>> = unionOfParameterKeys.associateWith { exampleName ->
        val queryParams = exampleQueryParams[exampleName] ?: emptyMap()
        val pathParams = examplePathParams[exampleName] ?: emptyMap()
        val headerParams = exampleHeaderParams[exampleName] ?: emptyMap()

        val path = toConcretePath(pathParams, httpPathPattern)

        val httpRequest =
            HttpRequest(method = httpMethod, path = path, queryParametersMap = queryParams, headers = headerParams)

        val requestsWithSecurityParams: List<HttpRequest> = securitySchemes.map { securityScheme ->
            securityScheme.addTo(httpRequest)
        }

        requestsWithSecurityParams
    }

}

private fun toConcretePath(
    pathParams: Map<String, String>,
    httpPathPattern: HttpPathPattern
): String {
    val path = pathParams.entries.fold(httpPathPattern.toOpenApiPath()) { acc, (key, value) ->
        acc.replace("{$key}", value)
    }
    return path
}
