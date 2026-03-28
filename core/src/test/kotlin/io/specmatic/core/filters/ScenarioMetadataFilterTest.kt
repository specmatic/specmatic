package io.specmatic.core.filters

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.Scenario
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Reasoning
import io.specmatic.test.TestSkipReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScenarioMetadataFilterTest {
    private data class FakeMetadata(private val scenario: Scenario) : HasScenarioMetadata {
        override fun toScenarioMetadata(): ExpressionContextPopulator = ScenarioFilterVariablePopulator(scenario)
    }

    private fun scenario(method: String = "GET", path: String = "/pets", status: Int = 200): Scenario {
        val scenario = mockk<Scenario>()
        every { scenario.method } returns method
        every { scenario.path } returns path
        every { scenario.status } returns status
        return scenario
    }

    @Test
    fun `filterUsingDecisions should keep matching execute decisions unchanged`() {
        val filter = ScenarioMetadataFilter.from("METHOD='GET'")
        val scenario = scenario()
        val decisions = sequenceOf(Decision.Execute(FakeMetadata(scenario), context = "kept"))
        val filtered = ScenarioMetadataFilter.filterUsingDecisions(decisions, filter).toList()
        assertThat(filtered).containsExactly(Decision.Execute(FakeMetadata(scenario), context = "kept"))
    }

    @Test
    fun `filterUsingDecisions should convert non matching execute decisions to excluded skips`() {
        val filter = ScenarioMetadataFilter.from("METHOD='POST'")
        val decisions = sequenceOf(Decision.Execute(FakeMetadata(scenario()), context = "original-context"))
        val filtered = ScenarioMetadataFilter.filterUsingDecisions(decisions, filter).toList()
        assertThat(filtered).containsExactly(Decision.Skip(context = "original-context", reasoning = Reasoning(mainReason = TestSkipReason.EXCLUDED)))
    }

    @Test
    fun `filterUsingDecisions should preserve existing skip decisions`() {
        val filter = ScenarioMetadataFilter.from("METHOD='POST'")
        val originalSkip = Decision.Skip(context = "already-skipped", reasoning = Reasoning(mainReason = TestSkipReason.EXAMPLES_REQUIRED))
        val filtered = ScenarioMetadataFilter.filterUsingDecisions(sequenceOf(originalSkip), filter).toList()
        assertThat(filtered).containsExactly(originalSkip)
    }
}
