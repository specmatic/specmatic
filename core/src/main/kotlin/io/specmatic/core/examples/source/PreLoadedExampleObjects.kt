package io.specmatic.core.examples.source

import io.specmatic.conversions.ExampleFromFile
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.LogStrategy
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.Row
import io.specmatic.mock.NoLogPrinter
import io.specmatic.mock.ScenarioStub

class PreLoadedExampleObjects(
    override val examples: Map<OpenApiSpecification.OperationIdentifier, List<Row>>
) : ExampleSource {
    constructor(examples: List<ScenarioStub>, specmaticConfig: SpecmaticConfig) : this(examples = transform(examples, specmaticConfig))

    companion object {
        private val noLogStrategy = Verbose(NoLogPrinter())
        fun transform(examples: List<ScenarioStub>, specmaticConfig: SpecmaticConfig): Map<OpenApiSpecification.OperationIdentifier, List<Row>> {
            return examples.map {
                val exampleFromFile = ExampleFromFile(it)
                OpenApiSpecification.OperationIdentifier(exampleFromFile) to exampleFromFile.toRow(specmaticConfig, noLogStrategy)
            }.groupBy { (operationIdentifier, _) -> operationIdentifier }.mapValues { (_, value) ->
                value.map { it.second }
            }

        }
    }
}