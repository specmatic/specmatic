package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.EmptyString
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode

internal class UndeclaredMediaType415Variant(
    private val requestPattern: HttpRequestPattern,
    private val metadata: UndeclaredRequestVariantMetadata
) : UndeclaredRequestVariant {
    override val responseStatus: Int = HttpStatusCode.UnsupportedMediaType.value

    override fun requestExampleToUseInsteadOfGenerating(requestExample: HttpRequest?): HttpRequest? =
        requestExample

    override fun applyToGeneratedRequest(request: HttpRequest, requestExample: HttpRequest?): HttpRequest {
        return requestWithUnsupportedContentTypeForExample(request)
    }

    override fun exactRequestPatternFor(request: HttpRequest, resolver: Resolver): UndeclaredRequestPatternResult =
        UndeclaredRequestPatternResult(
            requestPattern = requestPattern.generateExactHttpRequestPatternUsingWrongContentType(request, resolver),
            requestContentTypeForReport = requestPattern.headersPattern.contentType
        )

    override fun requestBelongsToPattern(request: HttpRequest, resolver: Resolver): Boolean =
        requestPattern.matchesPathStructureAndMethod(request, resolver).isSuccess()

    override fun exampleRequestBelongsToPattern(request: HttpRequest, resolver: Resolver): Boolean {
        val requestContentType = requestMediaType(request)
        val supportedContentTypes = supportedMediaTypes()

        return if (!requestContentType.isNullOrBlank() && supportedContentTypes.contains(requestContentType.lowercase())) {
            requestPattern.matches(request, resolver, resolver).isSuccess()
        } else {
            requestIdentifiesUnsupportedMediaTypeScenario(request, resolver).isSuccess()
        }
    }

    override fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result {
        val identityMatch = requestIdentifiesUnsupportedMediaTypeScenario(request, resolver)
        if (identityMatch is Result.Failure) return identityMatch

        return matchesBodyForUnsupportedMediaType(request)
    }

    private fun requestIdentifiesUnsupportedMediaTypeScenario(request: HttpRequest, resolver: Resolver): Result {
        val identifierMatch = requestPattern.matchesRequestIdentityIgnoringPayload(request, resolver)
        if (identifierMatch is Result.Failure) return identifierMatch

        val requestContentType = requestMediaType(request)
        val supportedContentTypes = supportedMediaTypes()
        if (requestContentType.isNullOrBlank()) {
            if (supportedContentTypes.isNotEmpty()) return Result.Success()

            return Result.Failure(
                message = "Request Content-Type is required for a 415 unsupported media type example",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestContentTypeBreadCrumbs()
        }

        if (supportedContentTypes.contains(requestContentType.lowercase())) {
            return Result.Failure(
                message = "Request Content-Type \"$requestContentType\" is supported by the specification, so this example should not return 415",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestContentTypeBreadCrumbs()
        }

        return Result.Success()
    }

    private fun matchesBodyForUnsupportedMediaType(request: HttpRequest): Result {
        val requestContentType = requestMediaType(request) ?: return Result.Success()
        val mediaType = requestContentType.lowercase()

        return when {
            request.hasNoBody() -> Result.Success()
            mediaType.isJsonMediaType() -> request.matchesJsonBodyForUnsupportedMediaType(requestContentType)
            mediaType.isXmlMediaType() -> request.matchesXmlBodyForUnsupportedMediaType(requestContentType)
            mediaType == "application/x-www-form-urlencoded" -> request.matchesFormBodyForUnsupportedMediaType(requestContentType)
            mediaType.isStringMediaType() -> request.matchesStringBodyForUnsupportedMediaType(requestContentType)
            else -> Result.Success()
        }
    }

    fun unsupportedContentTypeForExample(): String =
        unsupportedContentTypeForGeneratedExample()

    fun requestWithUnsupportedContentTypeForExample(request: HttpRequest): HttpRequest =
        request.useWrongContentType(unsupportedContentTypeForExample())

    fun requestWithValidUnsupportedContentTypeForExample(request: HttpRequest): HttpRequest =
        request.contentTypeToPreserveForUnsupportedMediaTypeExample()
            ?.let { request.useWrongContentType(it) }
            ?: requestWithUnsupportedContentTypeForExample(request)

    private fun unsupportedContentTypeForGeneratedExample(): String {
        val supportedContentTypes = supportedMediaTypes()

        return listOf("text/plain", "application/xml", "application/octet-stream", "application/x-www-form-urlencoded")
            .firstOrNull { it.baseMediaType()?.lowercase() !in supportedContentTypes }
            ?: "application/x-specmatic-unsupported"
    }

    private fun requestMediaType(request: HttpRequest): String? =
        request.contentType().baseMediaType()

    private fun supportedMediaTypes(): Set<String> =
        metadata.requestContentTypesForOperation.baseMediaTypes()

    private fun HttpRequest.contentTypeToPreserveForUnsupportedMediaTypeExample(): String? {
        val contentType = contentType()?.trim()?.takeIf(String::isNotBlank) ?: return null
        val mediaType = contentType.baseMediaType()?.lowercase() ?: return null

        if (mediaType in supportedMediaTypes()) return null
        if (!mediaType.hasBodyGeneratorForUnsupportedMediaTypeExample()) return null

        return contentType
    }

    private fun HttpRequest.hasNoBody(): Boolean =
        (body == EmptyString || body == NoBodyValue) && hasNoFormPayload()

    private fun HttpRequest.hasNoFormPayload(): Boolean =
        formFields.isEmpty() && multiPartFormData.isEmpty()

    private fun HttpRequest.matchesJsonBodyForUnsupportedMediaType(contentType: String): Result =
        if (hasNoFormPayload() && (body is JSONObjectValue || body is JSONArrayValue || body is StringValue))
            Result.Success()
        else
            unsupportedBodyFailure(contentType, "a JSON value")

    private fun HttpRequest.matchesXmlBodyForUnsupportedMediaType(contentType: String): Result =
        if (hasNoFormPayload() && body is XMLNode)
            Result.Success()
        else
            unsupportedBodyFailure(contentType, "XML")

    private fun HttpRequest.matchesFormBodyForUnsupportedMediaType(contentType: String): Result =
        if (formFields.isNotEmpty() && body == EmptyString && multiPartFormData.isEmpty())
            Result.Success()
        else
            unsupportedBodyFailure(contentType, "form fields")

    private fun HttpRequest.matchesStringBodyForUnsupportedMediaType(contentType: String): Result =
        if (hasNoFormPayload() && body is StringValue)
            Result.Success()
        else
            unsupportedBodyFailure(contentType, "a string value")

    private fun unsupportedBodyFailure(contentType: String, expectedBodyDescription: String): Result.Failure =
        Result.Failure(
            message = "Request body for Content-Type \"$contentType\" must be $expectedBodyDescription in a 415 unsupported media type example",
            failureReason = FailureReason.UndeclaredRequestVariantMismatch
        ).withRequestBodyBreadCrumbs()

    private fun String.isJsonMediaType(): Boolean =
        this == "application/json" || endsWith("+json")

    private fun String.isXmlMediaType(): Boolean =
        this == "application/xml" || this == "text/xml" || endsWith("+xml")

    private fun String.isStringMediaType(): Boolean =
        this == "text/plain" ||
                this == "application/octet-stream" ||
                this == "application/x-specmatic-unsupported"

    private fun String.hasBodyGeneratorForUnsupportedMediaTypeExample(): Boolean =
        isJsonMediaType() ||
                isXmlMediaType() ||
                this == "application/x-www-form-urlencoded" ||
                isStringMediaType()

    private fun HttpRequest.useWrongContentType(contentType: String): HttpRequest {
        val requestWithContentType = copy(
            headers = headers
                .filterKeys { !it.equals(CONTENT_TYPE, ignoreCase = true) }
                .plus(CONTENT_TYPE to contentType),
            formFields = emptyMap(),
            multiPartFormData = emptyList()
        )

        val mediaType = contentType.baseMediaType()?.lowercase()

        return when {
            mediaType?.isJsonMediaType() == true ->
                requestWithContentType.copy(body = JSONObjectValue(mapOf("specmatic" to StringValue("unsupported"))))
            mediaType?.isXmlMediaType() == true ->
                requestWithContentType.copy(body = toXMLNode("<xml-value>request</xml-value>"))
            mediaType == "application/x-www-form-urlencoded" -> requestWithContentType.copy(
                body = EmptyString,
                formFields = mapOf("specmatic" to "unsupported")
            )
            else -> requestWithContentType.copy(body = StringValue("unsupported request body"))
        }
    }
}

private fun Result.Failure.withRequestContentTypeBreadCrumbs(): Result.Failure {
    return breadCrumb(CONTENT_TYPE)
        .breadCrumb(BreadCrumb.HEADER.value)
        .breadCrumb(BreadCrumb.PARAMETERS.value)
        .breadCrumb(BreadCrumb.REQUEST.value)
}

private fun Result.Failure.withRequestBodyBreadCrumbs(): Result.Failure {
    return breadCrumb(BreadCrumb.BODY.value)
        .breadCrumb(BreadCrumb.REQUEST.value)
}
