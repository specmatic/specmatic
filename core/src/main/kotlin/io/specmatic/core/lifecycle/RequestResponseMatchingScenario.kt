package io.specmatic.core.lifecycle

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario

fun interface RequestResponseMatchingScenario {
    fun call(request: HttpRequest, response: HttpResponse, matchingScenario: Scenario)
}