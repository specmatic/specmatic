package io.specmatic.test.fixtures

import io.specmatic.core.Scenario

enum class FixtureScenarioType {
    POSITIVE,
    NEGATIVE;

    companion object {
        fun from(scenario: Scenario): FixtureScenarioType {
            return when (scenario.isNegative) {
                true -> NEGATIVE
                false -> POSITIVE
            }
        }
    }
}

data class FixtureExecutionMetadata(
    val scenarioType: FixtureScenarioType,
    val agentMode: Boolean = false,
) {
    companion object {
        fun from(testScenario: Scenario, agentMode: Boolean = false): FixtureExecutionMetadata {
            return FixtureExecutionMetadata(
                scenarioType = FixtureScenarioType.from(testScenario),
                agentMode = agentMode,
            )
        }
    }
}
