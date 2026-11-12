package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import io.specmatic.core.Result
import io.specmatic.core.pattern.ResultType.FailureResult
import io.specmatic.core.pattern.ResultType.SuccessResult
import java.util.stream.Stream

enum class ResultType {
    SuccessResult, FailureResult
}

internal class NodeOccurrenceTest {
    companion object {
        @JvmStatic
        fun params(): Stream<Arguments> = Stream.of(
            Arguments.of(NodeOccurrence.Multiple, NodeOccurrence.Optional, SuccessResult),
            Arguments.of(NodeOccurrence.Multiple, NodeOccurrence.Multiple, SuccessResult),
            Arguments.of(NodeOccurrence.Multiple, NodeOccurrence.Once, SuccessResult),
            Arguments.of(NodeOccurrence.Optional, NodeOccurrence.Multiple, FailureResult),
            Arguments.of(NodeOccurrence.Optional, NodeOccurrence.Optional, SuccessResult),
            Arguments.of(NodeOccurrence.Optional, NodeOccurrence.Once, SuccessResult),
            Arguments.of(NodeOccurrence.Once, NodeOccurrence.Multiple, FailureResult),
            Arguments.of(NodeOccurrence.Once, NodeOccurrence.Optional, FailureResult),
            Arguments.of(NodeOccurrence.Once, NodeOccurrence.Once, SuccessResult),
        )
    }

    @ParameterizedTest
    @MethodSource("params")
    fun temp(bigger: NodeOccurrence, smaller: NodeOccurrence, expectedResult: ResultType) {
        val result: Result = bigger.encompasses(smaller)

        val resultClass = when(expectedResult) {
            SuccessResult -> Result.Success::class.java
            FailureResult -> Result.Failure::class.java
        }

        assertThat(result).isInstanceOf(resultClass)
    }
}
