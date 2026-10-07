package io.specmatic.core.pipeline

import io.specmatic.core.Result
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasException
import io.specmatic.core.pattern.HasValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PipelineTest {
    @Nested
    inner class TypedStages {
        @Test
        fun `runs stages in order while changing the output type`() {
            val stages = mutableListOf<String>()
            val result = Pipeline.from(2)
                .then { value ->
                    stages.add("double")
                    HasValue("value:${value * 2}")
                }
                .then { value ->
                    stages.add("length")
                    HasValue(value.length)
                }
                .run()

            assertThat(stages).isEqualTo(listOf("double", "length"))
            assertThat(result).isEqualTo(HasValue(7))
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `a failure stops later stages`() {
            var laterStageWasRun = false
            val result = Pipeline.from(2)
                .then<Int> { HasFailure(Result.Failure("stop")) }
                .then { value ->
                    laterStageWasRun = true
                    HasValue(value.toString())
                }
                .run()

            assertThat(result).isEqualTo(HasFailure<String>(Result.Failure("stop")))
            assertThat(laterStageWasRun).isFalse()
        }

        @Test
        fun `an exception stops later stages and becomes a failed return value`() {
            val exception = IllegalStateException("stage failed")
            var laterStageWasRun = false
            val result = Pipeline.from(2)
                .then<Int> { throw exception }
                .then { value ->
                    laterStageWasRun = true
                    HasValue(value.toString())
                }
                .run()

            @Suppress("AssertBetweenInconvertibleTypes")
            assertThat(result).isEqualTo(HasException<Int>(exception, "", ""))
            assertThat(laterStageWasRun).isFalse()
        }
    }
}
