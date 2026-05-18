package io.specmatic.core

import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReferencedPatterns
import io.specmatic.reporter.internal.dto.bcc.ChangeStatus

data class ScenarioFingerprint(
    val status: Int,
    val requestContentType: String?,
    val responseContentType: String?,
    val httpRequestPattern: HttpRequestPattern,
    val httpResponsePattern: HttpResponsePattern,
    val referencedPatterns: Map<String, Pattern>,
) {
    companion object {
        fun from(scenario: Scenario): ScenarioFingerprint = ScenarioFingerprint(
            status = scenario.status,
            requestContentType = scenario.requestContentType,
            responseContentType = scenario.responseContentType,
            httpRequestPattern = scenario.httpRequestPattern,
            httpResponsePattern = scenario.httpResponsePattern,
            referencedPatterns = referencedPatternDefinitions(scenario),
        )

        fun changeStatusBetween(
            oldScenarios: Collection<Scenario>,
            newScenarios: Collection<Scenario>,
        ): ChangeStatus {
            val oldFingerprints = oldScenarios.map(::from).toSet()
            val newFingerprints = newScenarios.map(::from).toSet()
            return if (oldFingerprints == newFingerprints) ChangeStatus.UNCHANGED else ChangeStatus.CHANGED
        }

        private fun referencedPatternDefinitions(scenario: Scenario): Map<String, Pattern> {
            val referencedPatterns = ReferencedPatterns(scenario.patterns)
            scenario.httpRequestPattern.collectReferences(referencedPatterns)
            scenario.httpResponsePattern.collectReferences(referencedPatterns)
            return referencedPatterns.toMap()
        }
    }
}
