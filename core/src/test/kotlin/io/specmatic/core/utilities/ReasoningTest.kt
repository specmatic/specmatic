package io.specmatic.core.utilities

import io.specmatic.core.RuleViolationReport
import io.specmatic.test.TestSkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReasoningTest {
    @Test
    fun `withMainReason should promote the previous main reason into other reasons`() {
        val reasoning = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED, otherReasons = listOf(TestSkipReason.EXCLUDED))
        val updated = reasoning.withMainReason(TestSkipReason.ACCEPT_MISMATCH)
        assertThat(updated).isEqualTo(
            Reasoning(
                mainReason = TestSkipReason.ACCEPT_MISMATCH,
                otherReasons = listOf(TestSkipReason.EXAMPLES_REQUIRED, TestSkipReason.EXCLUDED)
            )
        )
    }

    @Test
    fun `withMainReason should set main reason on an empty reasoning`() {
        val updated = Reasoning().withMainReason(TestSkipReason.EXCLUDED)
        assertThat(updated).isEqualTo(Reasoning(mainReason = TestSkipReason.EXCLUDED, otherReasons = emptyList()))
    }

    @Test
    fun `toRuleViolationText should render unique violations only once and preserve insertion order`() {
        val text = Reasoning(
            mainReason = TestSkipReason.EXCLUDED,
            otherReasons = listOf(TestSkipReason.EXCLUDED, TestSkipReason.EXAMPLES_REQUIRED)
        ).toRuleViolationText()

        assertThat(text).isEqualTo(RuleViolationReport(linkedSetOf(TestSkipReason.EXCLUDED, TestSkipReason.EXAMPLES_REQUIRED)).toText())
        assertThat(text).containsSubsequence(TestSkipReason.EXCLUDED.id, TestSkipReason.EXAMPLES_REQUIRED.id)
    }
}
