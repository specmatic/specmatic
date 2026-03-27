package io.specmatic.core.utilities

import io.specmatic.core.RuleViolationReport
import io.specmatic.test.TestRuleViolations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReasoningTest {
    @Test
    fun `withMainReason should promote the previous main reason into other reasons`() {
        val reasoning = Reasoning(mainReason = TestRuleViolations.EXAMPLES_REQUIRED, otherReasons = listOf(TestRuleViolations.EXCLUDED))
        val updated = reasoning.withMainReason(TestRuleViolations.ACCEPT_MISMATCH)
        assertThat(updated).isEqualTo(
            Reasoning(
                mainReason = TestRuleViolations.ACCEPT_MISMATCH,
                otherReasons = listOf(TestRuleViolations.EXAMPLES_REQUIRED, TestRuleViolations.EXCLUDED)
            )
        )
    }

    @Test
    fun `withMainReason should set main reason on an empty reasoning`() {
        val updated = Reasoning().withMainReason(TestRuleViolations.EXCLUDED)
        assertThat(updated).isEqualTo(Reasoning(mainReason = TestRuleViolations.EXCLUDED, otherReasons = emptyList()))
    }

    @Test
    fun `toRuleViolationText should render unique violations only once and preserve insertion order`() {
        val text = Reasoning(
            mainReason = TestRuleViolations.EXCLUDED,
            otherReasons = listOf(TestRuleViolations.EXCLUDED, TestRuleViolations.EXAMPLES_REQUIRED)
        ).toRuleViolationText()

        assertThat(text).isEqualTo(RuleViolationReport(linkedSetOf(TestRuleViolations.EXCLUDED, TestRuleViolations.EXAMPLES_REQUIRED)).toText())
        assertThat(text).containsSubsequence(TestRuleViolations.EXCLUDED.id, TestRuleViolations.EXAMPLES_REQUIRED.id)
    }
}
