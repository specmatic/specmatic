package io.specmatic.core

import io.specmatic.reporter.internal.dto.operation.APIOperation
import io.specmatic.test.openAPIOperationFrom

interface ScenarioOperationProvider {
    fun operationFor(scenario: Scenario): APIOperation
}

object OpenApiScenarioOperationProvider : ScenarioOperationProvider {
    override fun operationFor(scenario: Scenario): APIOperation =
        openAPIOperationFrom(scenario, scenario.httpRequestPattern.httpPathPattern?.toOpenApiPath().orEmpty())
}
