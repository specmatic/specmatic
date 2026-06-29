package io.specmatic.core.examples.preprocessor

import io.specmatic.core.Result
import io.specmatic.core.value.Value
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

data class ExamplePreProcessResult(
    val result: Result,
    val outcome: Map<String, Value>,
    val attributes: PreProcessorAttributes = PreProcessorAttributes.Empty
)

interface ExamplePreProcessor {
    fun process(rawData: Map<String, Value>, filePath: String?): ExamplePreProcessResult

    companion object {
        private val registry = CopyOnWriteArrayList<ExamplePreProcessor>()

        init {
            val preProcessors = ServiceLoader.load(ExamplePreProcessor::class.java)
            registry.addAll(preProcessors.filterNotNull())
        }

        fun <T> withPreProcessor(processor: ExamplePreProcessor, block: () -> T): T {
            registry.add(processor)
            try {
                return block()
            } finally {
                registry.remove(processor)
            }
        }

        fun process(rawData: Map<String, Value>, filePath: String? = null): ExamplePreProcessResult {
            val initial = ExamplePreProcessResult(Result.Success(), rawData)
            return registry.fold(initial) { acc, processor ->
                val processed = processor.process(acc.outcome, filePath)
                ExamplePreProcessResult(
                    outcome = processed.outcome,
                    attributes = acc.attributes.merge(processed.attributes),
                    result = Result.fromResults(listOf(acc.result, processed.result)),
                )
            }
        }
    }
}
