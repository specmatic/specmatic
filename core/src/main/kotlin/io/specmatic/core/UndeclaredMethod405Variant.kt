package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.core.pattern.ContractException

internal class UndeclaredMethod405Variant(
    private val requestPattern: HttpRequestPattern,
    private val metadata: UndeclaredRequestVariantMetadata
) : UndeclaredRequestVariant {
    override val responseStatus: Int = HttpStatusCode.MethodNotAllowed.value

    override fun applyToGeneratedRequest(request: HttpRequest, requestExample: HttpRequest?): HttpRequest {
        val methodFromExample = requestExample?.method
        if (methodFromExample != null) return request.copy(method = methodFromExample)

        val generatedMethod = unsupportedMethod()
            ?: throw ContractException(
                errorMessage = noDisallowedMethodError(),
                failureReason = FailureReason.MethodNotAllowedNoDisallowedMethod
            )

        return request.copy(method = generatedMethod)
    }

    override fun exactRequestPatternFor(request: HttpRequest, resolver: Resolver): UndeclaredRequestPatternResult =
        UndeclaredRequestPatternResult(requestPattern.generateExactHttpRequestPatternUsingWrongMethod(request, resolver))

    override fun stubRequestPatternFor(request: HttpRequest, resolver: Resolver): HttpRequestPattern =
        requestPattern.generateExactHttpRequestPatternFrom(request, resolver)

    override fun requestBelongsToPattern(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = requestedMethod(request) ?: return false

        return when (requestedMethod) {
            requestPattern.method.orEmpty().uppercase() -> requestWithPatternMethodIdentifiesRequestPattern(request, resolver)
            in declaredMethodsForPath() -> false
            else -> requestWithRejectedMethodIdentifiesRequestPattern(request, resolver)
        }
    }

    override fun exampleRequestBelongsToPattern(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = requestedMethod(request) ?: return false

        return when (requestedMethod) {
            requestPattern.method.orEmpty().uppercase() -> requestWithPatternMethodIdentifiesRequestPattern(request, resolver)
            in declaredMethodsForPath() -> false
            else -> matchesUndeclaredRequest(request, resolver).isSuccess()
        }
    }

    override fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result {
        val requestedMethod = requestedMethod(request)
        if (requestedMethod == null || requestedMethod in declaredMethodsForPath()) {
            return Result.Failure(
                message = "Expected method not to be one of ${metadata.methodsForPath.sorted().joinToString()}",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestMethodBreadCrumbs()
        }

        val requestWithPatternMethod = request.copy(method = requestPattern.method)
        return requestPattern.matches(requestWithPatternMethod, resolver, resolver)
    }

    fun disallowedMethodForExample(): String? =
        unsupportedMethod()

    private fun unsupportedMethod(): String? {
        return (metadata.methodsForPath + requestPattern.method.orEmpty())
            .firstMethodNotDeclaredForPath()
    }

    private fun requestWithPatternMethodIdentifiesRequestPattern(request: HttpRequest, resolver: Resolver): Boolean {
        return requestPattern.matchesPathStructureAndMethod(request, resolver).isSuccess() &&
                requestMediaTypeMatchesPattern(request)
    }

    private fun requestWithRejectedMethodIdentifiesRequestPattern(request: HttpRequest, resolver: Resolver): Boolean {
        val requestWithPatternMethod = request.copy(method = requestPattern.method)
        return requestPattern.matchesPathStructureAndMethod(requestWithPatternMethod, resolver).isSuccess()
    }

    private fun requestedMethod(request: HttpRequest): String? =
        request.method.orEmpty().uppercase().takeUnless { it.isBlank() }

    private fun declaredMethodsForPath(): Set<String> =
        metadata.methodsForPath

    private fun requestMediaTypeMatchesPattern(request: HttpRequest): Boolean {
        val scenarioMediaType = requestPattern.headersPattern.contentType.baseMediaType()
        val requestMediaType = request.contentType().baseMediaType()

        return when {
            scenarioMediaType == null -> requestMediaType == null
            requestMediaType == null -> false
            else -> scenarioMediaType.equals(requestMediaType, ignoreCase = true)
        }
    }

    private fun noDisallowedMethodError(): String =
        "Cannot generate a 405 request for ${requestPattern.method} ${requestPattern.httpPathPattern?.toInternalPath()}: " +
                "all known HTTP methods are already declared for this path."
}

private fun Result.Failure.withRequestMethodBreadCrumbs(): Result.Failure {
    return breadCrumb(METHOD_BREAD_CRUMB).breadCrumb(BreadCrumb.REQUEST.value)
}
