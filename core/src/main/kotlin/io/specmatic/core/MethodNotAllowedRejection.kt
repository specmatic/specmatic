package io.specmatic.core

import io.ktor.http.HttpStatusCode

internal class MethodNotAllowedRejection(private val scenario: Scenario) : RequestRejectionBehavior {
    override val responseStatus: Int = HttpStatusCode.MethodNotAllowed.value

    override fun generateRejectedRequest(request: HttpRequest): HttpRequest {
        val methodFromExample = scenario.exampleRow?.requestExample?.method
        if (methodFromExample != null) return request.copy(method = methodFromExample)

        return request.copy(method = unsupportedMethod())
    }

    override fun canOwnRequest(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = request.method.orEmpty().uppercase()
        if (requestedMethod.isBlank()) return false

        return when (requestedMethod) {
            scenario.method.uppercase() -> requestWithScenarioMethodIdentifiesScenario(request, resolver)
            in scenario.requestRejectionMetadata.methodsForPath -> false
            else -> requestWithRejectedMethodIdentifiesScenario(request, resolver)
        }
    }

    override fun exampleBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = request.method.orEmpty().uppercase()
        if (requestedMethod.isBlank()) return false

        return when (requestedMethod) {
            scenario.method.uppercase() -> requestWithScenarioMethodIdentifiesScenario(request, resolver)
            in scenario.requestRejectionMetadata.methodsForPath -> false
            else -> matchesRejectedRequest(request, resolver).isSuccess()
        }
    }

    override fun matchesRejectedRequest(request: HttpRequest, resolver: Resolver): Result {
        val requestedMethod = request.method.orEmpty().uppercase()
        if (requestedMethod.isBlank() || requestedMethod in scenario.requestRejectionMetadata.methodsForPath) {
            return Result.Failure(
                message = "Expected method not to be one of ${scenario.requestRejectionMetadata.methodsForPath.sorted().joinToString()}",
                failureReason = FailureReason.RequestRejectionMismatch
            ).withRequestMethodBreadCrumbs().updateScenario(scenario)
        }

        val requestWithScenarioMethod = request.copy(method = scenario.method)
        return scenario.httpRequestPattern.matches(requestWithScenarioMethod, resolver, resolver)
    }

    private fun unsupportedMethod(): String {
        val supportedMethods = (scenario.requestRejectionMetadata.methodsForPath + scenario.method)
            .map { it.uppercase() }
            .toSet()

        return listOf("PATCH", "POST", "PUT", "DELETE", "GET", "HEAD", "OPTIONS", "TRACE")
            .firstOrNull { it !in supportedMethods }
            ?: "SPECMATIC-UNSUPPORTED"
    }

    private fun requestWithScenarioMethodIdentifiesScenario(request: HttpRequest, resolver: Resolver): Boolean {
        return scenario.httpRequestPattern.matchesPathStructureAndMethod(request, resolver).isSuccess() &&
                requestMediaTypeIdentifiesScenario(request)
    }

    private fun requestWithRejectedMethodIdentifiesScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestWithScenarioMethod = request.copy(method = scenario.method)
        return scenario.httpRequestPattern.matchesPathStructureAndMethod(requestWithScenarioMethod, resolver).isSuccess()
    }

    private fun requestMediaTypeIdentifiesScenario(request: HttpRequest): Boolean {
        val scenarioContentType = scenario.httpRequestPattern.headersPattern.contentType.requestRejectionNormalizedContentType()
        val requestContentType = request.contentType().requestRejectionNormalizedContentType()

        return when {
            scenarioContentType == null -> requestContentType == null
            requestContentType == null -> false
            else -> scenarioContentType.equals(requestContentType, ignoreCase = true)
        }
    }
}

private fun Result.Failure.withRequestMethodBreadCrumbs(): Result.Failure {
    return breadCrumb(METHOD_BREAD_CRUMB).breadCrumb(BreadCrumb.REQUEST.value)
}
