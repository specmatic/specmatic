package io.specmatic.core

import io.specmatic.reporter.internal.dto.bcc.ChangeStatus

data class ScenarioFingerprint(
    val status: Int,
    val requestContentType: String?,
    val responseContentType: String?,
    val httpRequestPattern: HttpRequestPattern,
    val httpResponsePattern: HttpResponsePattern,
) {
    data class Key(
        val path: String,
        val method: String,
        val requestContentType: String?,
        val status: Int,
        val responseContentType: String?,
    )

    companion object {
        fun from(scenario: Scenario): ScenarioFingerprint = ScenarioFingerprint(
            status = scenario.status,
            requestContentType = scenario.requestContentType,
            responseContentType = scenario.responseContentType,
            httpRequestPattern = scenario.httpRequestPattern,
            httpResponsePattern = scenario.httpResponsePattern,
        )

        fun keyOf(scenario: Scenario): Key = Key(
            path = scenario.path,
            method = scenario.method,
            requestContentType = scenario.requestContentType,
            status = scenario.status,
            responseContentType = scenario.responseContentType,
        )

        fun changeStatusBetween(
            oldScenarios: Collection<Scenario>,
            newScenarios: Collection<Scenario>,
        ): (Scenario) -> ChangeStatus {
            val oldByKey = oldScenarios.associate { keyOf(it) to from(it) }
            val newByKey = newScenarios.associate { keyOf(it) to from(it) }
            val statusByKey = (oldByKey.keys + newByKey.keys).associateWith { key ->
                if (oldByKey[key] == newByKey[key]) ChangeStatus.UNCHANGED else ChangeStatus.CHANGED
            }
            return { scenario -> statusByKey[keyOf(scenario)] ?: ChangeStatus.CHANGED }
        }
    }
}
