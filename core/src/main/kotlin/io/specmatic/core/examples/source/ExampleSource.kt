package io.specmatic.core.examples.source

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.Row
import io.specmatic.mock.ScenarioStub

interface ExampleSource {
    val examples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>
}