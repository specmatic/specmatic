package io.specmatic.core

import io.ktor.http.HttpStatusCode
import io.specmatic.core.pattern.Row
import io.specmatic.core.value.Value

internal class UndeclaredMediaTypeVariant(private val scenario: Scenario) : UndeclaredRequestVariant {
    override val responseStatus: Int = HttpStatusCode.UnsupportedMediaType.value

    override fun requestExampleForGeneration(): HttpRequest? =
        scenario.exampleRow?.requestExample

    override fun applyToGeneratedRequest(request: HttpRequest): HttpRequest {
        val unsupportedContentType = unsupportedContentTypeForGeneratedExample()
        val headersWithUnsupportedContentType = request.headers
            .filterKeys { !it.equals(CONTENT_TYPE, ignoreCase = true) }
            .plus(CONTENT_TYPE to unsupportedContentType)

        return request.copy(headers = headersWithUnsupportedContentType)
    }

    override fun requestPatternForStub(request: HttpRequest, resolver: Resolver): HttpRequestPattern {
        return scenario.httpRequestPattern.generateExactHttpRequestPatternUsingWrongContentType(request, resolver)
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
            httpRequestPattern = scenario.httpRequestPattern.generateExactHttpRequestPatternUsingWrongContentType(
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
            requestContentTypeForReport = scenario.httpRequestPattern.headersPattern.contentType,
        )
    }

    override fun canOwnRequest(request: HttpRequest, resolver: Resolver): Boolean =
        scenario.httpRequestPattern.matchesPathStructureAndMethod(request, resolver).isSuccess()

    override fun exampleBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean {
        val requestContentType = request.contentType().normalizedRequestVariantContentType()
        val supportedContentTypes = scenario.undeclaredRequestVariantMetadata.requestContentTypesForOperation.normalizedRequestVariantContentTypes()

        return if (!requestContentType.isNullOrBlank() && supportedContentTypes.contains(requestContentType.lowercase())) {
            scenario.httpRequestPattern.matches(request, resolver, resolver).isSuccess()
        } else {
            matchesUndeclaredRequest(request, resolver).isSuccess()
        }
    }

    override fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result {
        val identifierMatch = scenario.httpRequestPattern.matchesRequestIdentityIgnoringMediaType(request, resolver)
        if (identifierMatch is Result.Failure) return identifierMatch.updateScenario(scenario)

        val requestContentType = request.contentType().normalizedRequestVariantContentType()
        val supportedContentTypes = scenario.undeclaredRequestVariantMetadata.requestContentTypesForOperation.normalizedRequestVariantContentTypes()
        if (requestContentType.isNullOrBlank()) {
            if (supportedContentTypes.isNotEmpty()) return Result.Success()

            return Result.Failure(
                message = "Request Content-Type is required for a 415 unsupported media type example",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestContentTypeBreadCrumbs().updateScenario(scenario)
        }

        if (supportedContentTypes.contains(requestContentType.lowercase())) {
            return Result.Failure(
                message = "Request Content-Type \"$requestContentType\" is supported by the specification, so this example should not return 415",
                failureReason = FailureReason.UndeclaredRequestVariantMismatch
            ).withRequestContentTypeBreadCrumbs().updateScenario(scenario)
        }

        return Result.Success()
    }

    private fun unsupportedContentTypeForGeneratedExample(): String {
        val supportedContentTypes = scenario.undeclaredRequestVariantMetadata.requestContentTypesForOperation.normalizedRequestVariantContentTypes()

        return listOf("text/plain", "application/xml", "application/octet-stream", "application/x-www-form-urlencoded")
            .firstOrNull { it.normalizedRequestVariantContentType()?.lowercase() !in supportedContentTypes }
            ?: "application/x-specmatic-unsupported"
    }
}

private fun Result.Failure.withRequestContentTypeBreadCrumbs(): Result.Failure {
    return breadCrumb(CONTENT_TYPE)
        .breadCrumb(BreadCrumb.HEADER.value)
        .breadCrumb(BreadCrumb.PARAMETERS.value)
        .breadCrumb(BreadCrumb.REQUEST.value)
}
