package io.specmatic.core

import io.specmatic.reporter.internal.dto.bcc.ChangeStatus

data class ScenarioFingerprint(
    val status: Int,
    val requestContentType: String?,
    val responseContentType: String?,
    val httpRequestPattern: HttpRequestPattern,
    val httpResponsePattern: HttpResponsePattern,
) {
    companion object {
        fun from(scenario: Scenario): ScenarioFingerprint = ScenarioFingerprint(
            status = scenario.status,
            requestContentType = scenario.requestContentType,
            responseContentType = scenario.responseContentType,
            httpRequestPattern = scenario.httpRequestPattern,
            httpResponsePattern = scenario.httpResponsePattern,
        )

        fun changeStatusBetween(
            oldScenarios: Collection<Scenario>,
            newScenarios: Collection<Scenario>,
        ): ChangeStatus {
            val oldFingerprints = oldScenarios.map(::from).toSet()
            val newFingerprints = newScenarios.map(::from).toSet()
            return if (oldFingerprints == newFingerprints) ChangeStatus.UNCHANGED else ChangeStatus.CHANGED
        }
    }
}
