package io.specmatic.test

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.Row.Companion.isNullOrEmpty

interface RequestValidator {
    fun validate(feature: Feature, scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest): Result
}

object DefaultRequestValidator: RequestValidator {
    override fun validate(feature: Feature, scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest): Result {
        val isClientErrorExample = originalScenario.isA4xxScenario() && !scenario.exampleRow.isNullOrEmpty()
        if (scenario.isNegative || isClientErrorExample) {
            val resolver = feature.flagsBased.update(originalScenario.resolver)
            val requestPattern = originalScenario.httpRequestPattern
            return requestPattern.matchesUndeclaredRequestFor(originalScenario.status, httpRequest, resolver)
                ?: requestPattern.matchesPathStructureMethodAndContentType(httpRequest, resolver)
        }
        return originalScenario.matchesRequest(httpRequest, feature.flagsBased)
    }
}
