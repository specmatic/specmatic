package io.specmatic.conversions

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.MultiPartContentValue
import io.specmatic.core.MultiPartFileValue
import io.specmatic.core.MultiPartFormDataValue
import io.specmatic.core.NoBodyValue
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.JSONObjectValue

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
                            body = parsedValue(bodyValue))
                }
            } else {
                val httpRequest = HttpRequest(
                    method = httpMethod,
                    path = httpPathPattern.toInternalPath(),
                    headers = mapOf("Content-Type" to contentType),
                    body = parsedValue(bodyValue)
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

    fun examplesWithFormFields(exampleFormFields: Map<String, Map<String, String>>, contentType: String): Map<String, List<HttpRequest>> {
        val examplesWithFormFields: Map<String, List<HttpRequest>> = exampleFormFields.mapValues { (exampleName, formFields) ->
            val requests: List<HttpRequest> = if (exampleName in examplesBasedOnParameters) {
                examplesBasedOnParameters.getValue(exampleName).map { exampleRequest ->
                    exampleRequest.withFormFields(formFields, contentType)
                }
            } else {
                val httpRequest = HttpRequest(
                    method = httpMethod,
                    path = httpPathPattern.toInternalPath(),
                    headers = mapOf(CONTENT_TYPE to contentType),
                    formFields = formFields
                )

                securitySchemes.map { securityScheme ->
                    securityScheme.addTo(httpRequest)
                }
            }

            requests
        }

        val examplesWithoutFormFields = (examplesBasedOnParameters.keys - exampleFormFields.keys).associateWith { key -> examplesBasedOnParameters.getValue(key) }

        return examplesWithFormFields + examplesWithoutFormFields
    }

    fun examplesWithMultiPartFormData(exampleFormFields: Map<String, Map<String, String>>, contentType: String): Map<String, List<HttpRequest>> {
        val examplesWithMultiPartFormData: Map<String, List<HttpRequest>> = exampleFormFields.mapValues { (exampleName, formFields) ->
            val multiPartFormData = formFields.map { (partName, partValue) ->
                multiPartFormDataValue(partName, partValue)
            }

            if (exampleName in examplesBasedOnParameters) {
                examplesBasedOnParameters.getValue(exampleName).map { exampleRequest ->
                    exampleRequest.withMultiPartFormData(multiPartFormData, contentType)
                }
            } else {
                val httpRequest = HttpRequest(
                    method = httpMethod,
                    path = httpPathPattern.toInternalPath(),
                    headers = mapOf(CONTENT_TYPE to contentType),
                    multiPartFormData = multiPartFormData
                )

                securitySchemes.map { securityScheme ->
                    securityScheme.addTo(httpRequest)
                }
            }
        }

        val examplesWithoutMultiPartFormData = (examplesBasedOnParameters.keys - exampleFormFields.keys)
            .associateWith { key -> examplesBasedOnParameters.getValue(key) }

        return examplesWithMultiPartFormData + examplesWithoutMultiPartFormData
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

private fun HttpRequest.withFormFields(formFields: Map<String, String>, contentType: String): HttpRequest {
    val contentTypeHeader = if (headers.keys.any { it.equals(CONTENT_TYPE, ignoreCase = true) }) {
        emptyMap()
    } else {
        mapOf(CONTENT_TYPE to contentType)
    }

    return copy(
        headers = headers + contentTypeHeader,
        formFields = formFields,
        body = NoBodyValue
    )
}

private fun HttpRequest.withMultiPartFormData(multiPartFormData: List<MultiPartFormDataValue>, contentType: String): HttpRequest {
    val contentTypeHeader = if (headers.keys.any { it.equals(CONTENT_TYPE, ignoreCase = true) }) {
        emptyMap()
    } else {
        mapOf(CONTENT_TYPE to contentType)
    }

    return copy(
        headers = headers + contentTypeHeader,
        multiPartFormData = multiPartFormData,
        body = NoBodyValue
    )
}

private fun multiPartFormDataValue(partName: String, partValue: String): MultiPartFormDataValue {
    val parsedPartValue = parsedValue(partValue)
    val externalValue = (parsedPartValue as? JSONObjectValue)?.jsonObject?.get("externalValue")?.toStringLiteral()

    return when {
        externalValue != null -> MultiPartFileValue(partName, externalValue)
        else -> MultiPartContentValue(partName, parsedPartValue)
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
