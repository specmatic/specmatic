package io.specmatic.core.examples.source

import io.specmatic.core.Feature
import io.specmatic.mock.ScenarioStub

data class FeatureAndExamples(
    val feature: Feature,
    val unusedExamples: Set<String> = emptySet(),
    val externalExamples: List<ScenarioStub> = emptyList(),
)