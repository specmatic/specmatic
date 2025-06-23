package io.specmatic.core.lifecycle

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario

class RequestResponseMatchingScenarioHooks : RequestResponseMatchingScenario {
    private val requestResponseMatchingScenario: MutableList<RequestResponseMatchingScenario> = mutableListOf()

    fun register(hook: RequestResponseMatchingScenario) {
        requestResponseMatchingScenario.add(hook)
    }

    fun remove(hook: RequestResponseMatchingScenario) {
        requestResponseMatchingScenario.remove(hook)
    }

    override fun call(request: HttpRequest, response: HttpResponse, matchingScenario: Scenario) {
        requestResponseMatchingScenario.forEach() {
            it.call(request, response, matchingScenario)
        }
    }
}
