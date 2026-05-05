package io.specmatic.core.utilities

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EarlyResultTest {
    @Test
    fun `fold should invoke success branch for FirstSuccess`() {
        val result: EarlyResult<String, String> = EarlyResult.FirstSuccess("ok")

        val value = result.fold(
            onSuccess = { success -> "success:$success" },
            onFailure = { failures -> "failure:${failures.joinToString(",")}" }
        )

        assertThat(value).isEqualTo("success:ok")
    }

    @Test
    fun `fold should invoke failure branch for Failures`() {
        val result: EarlyResult<String, String> = EarlyResult.Failures(listOf("one", "two"))

        val value = result.fold(
            onSuccess = { success -> "success:$success" },
            onFailure = { failures -> "failure:${failures.joinToString(",")}" }
        )

        assertThat(value).isEqualTo("failure:one,two")
    }

    @Test
    fun `getOrElse should return success value when present`() {
        val result: EarlyResult<String, String> = EarlyResult.FirstSuccess("ok")

        val value = result.getOrElse { failures -> "fallback:${failures.joinToString(",")}" }

        assertThat(value).isEqualTo("ok")
    }

    @Test
    fun `getOrElse should use fallback when failures are present`() {
        val result: EarlyResult<String, String> = EarlyResult.Failures(listOf("one", "two"))

        val value = result.getOrElse { failures -> "fallback:${failures.joinToString(",")}" }

        assertThat(value).isEqualTo("fallback:one,two")
    }

    @Test
    fun `firstSuccessOrFailures should return first success and stop evaluating items`() {
        val evaluated = mutableListOf<Int>()
        val result = listOf(1, 2, 3).firstSuccessOrFailures(
            evaluate = { item ->
                evaluated.add(item)
                item * 10
            },
            isSuccess = { value -> value == 20 },
            toFailure = { value -> "failure:$value" }
        )

        assertThat(result).isEqualTo(EarlyResult.FirstSuccess(20))
        assertThat(evaluated).containsExactly(1, 2)
    }

    @Test
    fun `firstSuccessOrFailures should collect failures when no item succeeds`() {
        val result = listOf(1, 2, 3).firstSuccessOrFailures(
            evaluate = { item -> item * 10 },
            isSuccess = { false },
            toFailure = { value -> "failure:$value" }
        )

        assertThat(result).isEqualTo(EarlyResult.Failures(listOf("failure:10", "failure:20", "failure:30")))
    }
}
