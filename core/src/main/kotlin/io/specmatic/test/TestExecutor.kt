package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.value.Value

interface TestExecutor {
    fun execute(request: HttpRequest): HttpResponse

    fun setServerState(serverState: Map<String, Value>) {
    }

    fun preExecuteScenario(scenario: Scenario, request: HttpRequest) {
    }

    fun postExecuteScenario(scenario: Scenario) {
    }
}
