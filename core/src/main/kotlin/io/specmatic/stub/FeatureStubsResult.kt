package io.specmatic.stub

import io.specmatic.core.Feature
import io.specmatic.mock.ScenarioStub

sealed class FeatureStubsResult {

    data class Success(
        val feature: Feature,
        val scenarioStubs: List<ScenarioStub>
    ) : FeatureStubsResult()

    data class Failure(
        val stubFile: String,
        val errorMessage: String
    ) : FeatureStubsResult()
}
