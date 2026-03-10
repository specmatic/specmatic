package io.specmatic.core.examples.source

import io.specmatic.mock.ScenarioStub

interface ExampleSource {
    val examples: List<ScenarioStub>
}