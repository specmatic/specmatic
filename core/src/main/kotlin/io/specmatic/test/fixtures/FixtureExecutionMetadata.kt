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

data class FixtureExecutionMetadata(val scenarioType: FixtureScenarioType) {
    companion object {
        fun from(testScenario: Scenario): FixtureExecutionMetadata {
            return FixtureExecutionMetadata(scenarioType = FixtureScenarioType.from(testScenario))
        }
    }
}
