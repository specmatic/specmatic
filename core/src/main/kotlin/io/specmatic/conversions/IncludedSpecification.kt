package io.specmatic.conversions

import io.specmatic.core.NamedStub
import io.specmatic.core.ScenarioInfo
import io.cucumber.messages.types.Step

interface IncludedSpecification {
    fun toScenarioInfos(): Pair<List<ScenarioInfo>, List<NamedStub>>
    fun matches(
        specmaticScenarioInfo: ScenarioInfo,
        steps: List<Step>
    ): List<ScenarioInfo>
}
