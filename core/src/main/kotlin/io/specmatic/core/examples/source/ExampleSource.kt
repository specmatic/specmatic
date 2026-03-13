package io.specmatic.core.examples.source

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.Row

interface ExampleSource {
    val examples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>
}

class CombinedSource(val sources: List<ExampleSource>) : ExampleSource {
    override val examples: Map<OpenApiSpecification.OperationIdentifier, List<Row>> get() {
        return sources
            .flatMap { it.examples.entries }
            .groupBy(keySelector = { it.key }, valueTransform = { it.value })
            .mapValues { (_, lists) -> lists.flatten() }
    }
}
