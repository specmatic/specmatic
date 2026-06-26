package io.specmatic.test

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.Scenario

interface RequestValidator {
    fun validate(feature: Feature, scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest): Result
}

object DefaultRequestValidator: RequestValidator {
    override fun validate(feature: Feature, scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest): Result {
        return originalScenario.matchesRequest(httpRequest, feature.flagsBased)
    }
}
