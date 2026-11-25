package io.specmatic.test.fixtures

import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig

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

data class FixtureExecutionMetadata(val scenarioType: FixtureScenarioType, val specmaticConfig: SpecmaticConfig = SpecmaticConfig()) {
    companion object {
        fun from(testScenario: Scenario, specmaticConfig: SpecmaticConfig = SpecmaticConfig()): FixtureExecutionMetadata {
            return FixtureExecutionMetadata(
                specmaticConfig = specmaticConfig,
                scenarioType = FixtureScenarioType.from(testScenario),
            )
        }
    }
}
