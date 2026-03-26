package io.specmatic.core.utilities

import io.specmatic.test.TestRuleViolations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DecisionTest {
    @Test
    fun `mapValue should transform execute values and preserve context and reasoning`() {
        val decision = Decision.Execute(value = 10, context = "ctx", reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        val mapped = decision.mapValue { it * 2 }
        assertThat(mapped).isEqualTo(
            Decision.Execute(
                value = 20,
                context = "ctx",
                reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES)
            )
        )
    }

    @Test
    fun `mapValue should preserve skip decisions unchanged`() {
        val decision = Decision.Skip(context = "ctx", reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        val mapped = decision.mapValue { it }
        assertThat(mapped).isEqualTo(decision)
    }

    @Test
    fun `map should transform execute values using item and context`() {
        val decision = Decision.Execute(value = 10, context = 5, reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        val mapped = decision.map { value, context -> value + context }
        assertThat(mapped).isEqualTo(
            Decision.Execute(
                value = 15,
                context = 5,
                reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES)
            )
        )
    }

    @Test
    fun `flatMap should allow replacing execute decisions`() {
        val decision = Decision.Execute(value = 10, context = "ctx", reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        val mapped = decision.flatMap { value, context, reasoning ->
            Decision.Execute(value + 1, "$context-updated", reasoning.withMainReason(TestRuleViolations.EXCLUDED))
        }

        assertThat(mapped).isEqualTo(
            Decision.Execute(
                value = 11,
                context = "ctx-updated",
                reasoning = Reasoning(mainReason = TestRuleViolations.EXCLUDED, otherReasons = listOf(TestRuleViolations.NO_EXAMPLES))
            )
        )
    }

    @Test
    fun `flatMap should preserve skip decisions unchanged`() {
        val decision = Decision.Skip(context = "ctx", reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES)) as Decision<*, *>
        val mapped = decision.flatMap { value, context, reasoning ->
            Decision.Execute(value, context, reasoning)
        }

        assertThat(mapped).isEqualTo(decision)
    }

    @Test
    fun `flatMapSequence should return mapped sequence for execute decisions`() {
        val decision = Decision.Execute(value = 2, context = "ctx", reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        val mapped = decision.flatMapSequence { value, context, reasoning ->
            sequenceOf(Decision.Execute(value, context, reasoning), Decision.Execute(value + 1, "$context-next", reasoning))
        }

        assertThat(mapped.toList()).containsExactly(
            Decision.Execute(2, "ctx", Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES)),
            Decision.Execute(3, "ctx-next", Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        )
    }

    @Test
    fun `flatMapSequence should preserve skip decisions unchanged`() {
        val decision = Decision.Skip(context = "ctx", reasoning = Reasoning(mainReason = TestRuleViolations.NO_EXAMPLES))
        val mapped = decision.flatMapSequence { _, _, _ ->
            sequenceOf(Decision.Execute(1, "other", Reasoning()))
        }

        assertThat(mapped.toList()).containsExactly(decision)
    }
}
