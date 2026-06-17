package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.core.pattern.Row
import io.specmatic.core.value.Value

internal class UndeclaredMethod405Variant(private val scenario: Scenario) : UndeclaredRequestVariant {
    override val responseStatus: Int = HttpStatusCode.MethodNotAllowed.value

    override fun toUndeclaredRequest(request: HttpRequest): HttpRequest {
        val methodFromExample = scenario.exampleRow?.requestExample?.method
        if (methodFromExample != null) return request.copy(method = methodFromExample)

        return request.copy(method = unsupportedMethod())
    }

    override fun scenarioFromExampleRow(
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

    override fun requestBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = requestedMethod(request) ?: return false

        return when (requestedMethod) {
            scenario.method.uppercase() -> requestWithScenarioMethodIdentifiesScenario(request, resolver)
            in declaredMethodsForPath() -> false
            else -> requestWithRejectedMethodIdentifiesScenario(request, resolver)
        }
    }

    override fun exampleRequestBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestedMethod = requestedMethod(request) ?: return false

        return when (requestedMethod) {
            scenario.method.uppercase() -> requestWithScenarioMethodIdentifiesScenario(request, resolver)
            in declaredMethodsForPath() -> false
            else -> matchesUndeclaredRequest(request, resolver).isSuccess()
        }
    }

    override fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result {
        val requestedMethod = requestedMethod(request)
        if (requestedMethod == null || requestedMethod in declaredMethodsForPath()) {
            return Result.Failure(
                message = "Expected method not to be one of ${scenario.undeclaredRequestVariantMetadata.methodsForPath.sorted().joinToString()}",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestMethodBreadCrumbs().updateScenario(scenario)
        }

        val requestWithScenarioMethod = request.copy(method = scenario.method)
        return scenario.httpRequestPattern.matches(requestWithScenarioMethod, resolver, resolver)
    }

    override fun disallowedMethodFor405Example(): String =
        unsupportedMethod()

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
                requestMediaTypeMatchesScenario(request)
    }

    private fun requestWithRejectedMethodIdentifiesScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestWithScenarioMethod = request.copy(method = scenario.method)
        return scenario.httpRequestPattern.matchesPathStructureAndMethod(requestWithScenarioMethod, resolver).isSuccess()
    }

    private fun requestedMethod(request: HttpRequest): String? =
        request.method.orEmpty().uppercase().takeUnless { it.isBlank() }

    private fun declaredMethodsForPath(): Set<String> =
        scenario.undeclaredRequestVariantMetadata.methodsForPath

    private fun requestMediaTypeMatchesScenario(request: HttpRequest): Boolean {
        val scenarioMediaType = scenario.httpRequestPattern.headersPattern.contentType.baseMediaType()
        val requestMediaType = request.contentType().baseMediaType()

        return when {
            scenarioMediaType == null -> requestMediaType == null
            requestMediaType == null -> false
            else -> scenarioMediaType.equals(requestMediaType, ignoreCase = true)
        }
    }
}

private fun Result.Failure.withRequestMethodBreadCrumbs(): Result.Failure {
    return breadCrumb(METHOD_BREAD_CRUMB).breadCrumb(BreadCrumb.REQUEST.value)
}
