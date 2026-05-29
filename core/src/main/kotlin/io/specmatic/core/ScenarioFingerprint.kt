package io.specmatic.core

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
            // Drop the ambient sibling-path context so that an operation's change status reflects
            // only its own contract - otherwise a path-pattern change in one operation would flip
            // the change status of every other operation sharing the same HTTP method.
            httpRequestPattern = scenario.httpRequestPattern.let { request ->
                request.copy(httpPathPattern = request.httpPathPattern?.withoutOtherPathPatterns())
            },
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
