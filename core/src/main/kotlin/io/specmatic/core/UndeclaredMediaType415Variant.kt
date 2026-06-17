package io.specmatic.core

import io.ktor.http.HttpStatusCode

internal class UndeclaredMediaType415Variant(
    private val requestPattern: HttpRequestPattern,
    private val metadata: UndeclaredRequestVariantMetadata
) : UndeclaredRequestVariant {
    override val responseStatus: Int = HttpStatusCode.UnsupportedMediaType.value

    override fun requestExampleToUseInsteadOfGenerating(requestExample: HttpRequest?): HttpRequest? =
        requestExample

    override fun applyToGeneratedRequest(request: HttpRequest, requestExample: HttpRequest?): HttpRequest {
        val unsupportedContentType = unsupportedContentTypeForGeneratedExample()
        val headersWithUnsupportedContentType = request.headers
            .filterKeys { !it.equals(CONTENT_TYPE, ignoreCase = true) }
            .plus(CONTENT_TYPE to unsupportedContentType)

        return request.copy(headers = headersWithUnsupportedContentType)
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
            matchesUndeclaredRequest(request, resolver).isSuccess()
        }
    }

    override fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result {
        val identifierMatch = requestPattern.matchesRequestIdentityIgnoringMediaType(request, resolver)
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

    fun unsupportedContentTypeForExample(): String =
        unsupportedContentTypeForGeneratedExample()

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
}

private fun Result.Failure.withRequestContentTypeBreadCrumbs(): Result.Failure {
    return breadCrumb(CONTENT_TYPE)
        .breadCrumb(BreadCrumb.HEADER.value)
        .breadCrumb(BreadCrumb.PARAMETERS.value)
        .breadCrumb(BreadCrumb.REQUEST.value)
}
