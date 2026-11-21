package io.specmatic.test.fixtures

import io.specmatic.core.GeneratedScenarioOrigin
import io.specmatic.core.Scenario

enum class FixtureScenarioType {
    POSITIVE,
    NEGATIVE,
    EXAMPLE;

    companion object {
        fun from(scenario: Scenario): FixtureScenarioType {
            if (scenario.isNegative) return NEGATIVE
            if (scenario.generatedFrom == GeneratedScenarioOrigin.EXAMPLE_ROW) return EXAMPLE
            return POSITIVE
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
