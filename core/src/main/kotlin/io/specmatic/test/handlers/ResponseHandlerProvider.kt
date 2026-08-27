package io.specmatic.test.handlers

import io.specmatic.core.Feature
import io.specmatic.core.Scenario

interface ResponseHandlerProvider {
    fun handlersFor(feature: Feature, originalScenario: Scenario): List<ResponseHandler>
}
