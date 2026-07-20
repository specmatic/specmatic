package io.specmatic.core

import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.reporter.model.OpenAPIOperation

interface ScenarioOperationProvider {
    fun operationFor(scenario: Scenario): APIOperation
}

object OpenApiScenarioOperationProvider : ScenarioOperationProvider {
    override fun operationFor(scenario: Scenario): APIOperation =
        OpenAPIOperation(
            path = scenario.httpRequestPattern.httpPathPattern?.toOpenApiPath().orEmpty(),
            method = scenario.soapActionUnescaped ?: scenario.method,
            contentType = scenario.requestContentType,
            responseCode = scenario.status,
            protocol = scenario.protocol,
            responseContentType = scenario.responseContentType
        )

}
