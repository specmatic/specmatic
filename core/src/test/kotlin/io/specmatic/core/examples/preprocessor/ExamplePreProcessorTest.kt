package io.specmatic.core.examples.preprocessor

import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ExamplePreProcessorTest {
    @Test
    fun `processors should run in order and merge attributes`() {
        val firstKey = object : PreProcessorAttributes.Key<String> {}
        val secondKey = object : PreProcessorAttributes.Key<Int> {}

        val firstProcessor = object : ExamplePreProcessor {
            override fun process(rawData: Map<String, io.specmatic.core.value.Value>, filePath: String?): ExamplePreProcessResult {
                return ExamplePreProcessResult(
                    result = Result.Success(),
                    outcome = rawData + ("first" to StringValue("one")),
                    attributes = PreProcessorAttributes.Empty.put(firstKey, "alpha")
                )
            }
        }

        val secondProcessor = object : ExamplePreProcessor {
            override fun process(rawData: Map<String, io.specmatic.core.value.Value>, filePath: String?): ExamplePreProcessResult {
                assertThat(rawData["first"]).isEqualTo(StringValue("one"))
                return ExamplePreProcessResult(
                    result = Result.Success(),
                    outcome = rawData + ("second" to StringValue("two")),
                    attributes = PreProcessorAttributes.Empty.put(secondKey, 2)
                )
            }
        }

        ExamplePreProcessor.withPreProcessor(firstProcessor) {
            ExamplePreProcessor.withPreProcessor(secondProcessor) {
                val result = ExamplePreProcessor.process(emptyMap())
                assertThat(result.outcome["first"]).isEqualTo(StringValue("one"))
                assertThat(result.outcome["second"]).isEqualTo(StringValue("two"))
                assertThat(result.attributes[firstKey]).isEqualTo("alpha")
                assertThat(result.attributes[secondKey]).isEqualTo(2)
                assertThat(result.result.isSuccess()).isTrue()
            }
        }
    }
}
