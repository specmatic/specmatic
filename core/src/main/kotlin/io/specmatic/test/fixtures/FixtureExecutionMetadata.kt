package io.specmatic.test.fixtures

import io.specmatic.core.GeneratedScenarioOrigin
import io.specmatic.core.Scenario
import io.specmatic.core.SpecmaticConfig

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
