package io.specmatic.core.examples.source

import io.specmatic.mock.ScenarioStub

class NoExampleSource : ExampleSource {
    override val examples: List<ScenarioStub> = emptyList()
}