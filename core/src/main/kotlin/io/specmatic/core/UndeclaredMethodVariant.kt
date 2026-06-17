package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.core.pattern.Row
import io.specmatic.core.value.Value

internal class UndeclaredMethodVariant(private val scenario: Scenario) : UndeclaredRequestVariant {
    override val responseStatus: Int = HttpStatusCode.MethodNotAllowed.value

    override fun applyToGeneratedRequest(request: HttpRequest): HttpRequest {
        val methodFromExample = scenario.exampleRow?.requestExample?.method
        if (methodFromExample != null) return request.copy(method = methodFromExample)

        return request.copy(method = unsupportedMethod())
    }

    override fun scenarioFromRequestExampleRow(
        row: Row,
        resolver: Resolver,
        newExpectedFacts: Map<String, Value>,
        ignoreFailure: Boolean,
        generativePrefix: String
    ): Scenario? {
        val requestExample = row.requestExample ?: return null
        val newResponsePattern = scenario.httpResponsePattern.withResponseExampleValue(row, resolver)

        return scenario.copy(
            httpRequestPattern = scenario.httpRequestPattern.generateExactHttpRequestPatternUsingWrongMethod(
                requestExample,
                resolver
            ),
            httpResponsePattern = newResponsePattern,
            expectedFacts = newExpectedFacts,
            ignoreFailure = ignoreFailure,
            exampleName = row.name,
            exampleRow = row,
            generatedFrom = GeneratedScenarioOrigin.EXAMPLE_ROW,
            generativePrefix = generativePrefix,
        )
    }

    override fun canOwnRequest(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = request.method.orEmpty().uppercase()
        if (requestedMethod.isBlank()) return false

        return when (requestedMethod) {
            scenario.method.uppercase() -> requestWithScenarioMethodIdentifiesScenario(request, resolver)
            in scenario.undeclaredRequestVariantMetadata.methodsForPath -> false
            else -> requestWithRejectedMethodIdentifiesScenario(request, resolver)
        }
    }

    override fun exampleBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = request.method.orEmpty().uppercase()
        if (requestedMethod.isBlank()) return false

        return when (requestedMethod) {
            scenario.method.uppercase() -> requestWithScenarioMethodIdentifiesScenario(request, resolver)
            in scenario.undeclaredRequestVariantMetadata.methodsForPath -> false
            else -> matchesUndeclaredRequest(request, resolver).isSuccess()
        }
    }

    override fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result {
        val requestedMethod = request.method.orEmpty().uppercase()
        if (requestedMethod.isBlank() || requestedMethod in scenario.undeclaredRequestVariantMetadata.methodsForPath) {
            return Result.Failure(
                message = "Expected method not to be one of ${scenario.undeclaredRequestVariantMetadata.methodsForPath.sorted().joinToString()}",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestMethodBreadCrumbs().updateScenario(scenario)
        }

        val requestWithScenarioMethod = request.copy(method = scenario.method)
        return scenario.httpRequestPattern.matches(requestWithScenarioMethod, resolver, resolver)
    }

    private fun unsupportedMethod(): String {
        val supportedMethods = (scenario.undeclaredRequestVariantMetadata.methodsForPath + scenario.method)
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
        val scenarioContentType = scenario.httpRequestPattern.headersPattern.contentType.normalizedRequestVariantContentType()
        val requestContentType = request.contentType().normalizedRequestVariantContentType()

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
