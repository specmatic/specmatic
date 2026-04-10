package io.specmatic.core

class BadRequestOrDefault(val badRequestResponses: Map<Int, List<Scenario>> = emptyMap(), val defaultResponses: List<Scenario> = emptyList()) {
    fun matches(httpResponse: HttpResponse, resolver: Resolver): Result {
        val bestMatch = findBestMatchingScenario(httpResponse) ?: return Result.Failure("No matching or fallback response found for status ${httpResponse.status}.")
        val result = bestMatch.scenario.httpResponsePattern.matchesResponse(httpResponse, resolver)
        return if (bestMatch.fromDefault) {
            result.partialSuccess("The response matched the default response, but the contract should declare a ${httpResponse.status} response.")
        } else {
            result
        }
    }

    fun updateScenarioWithResponse(httpResponse: HttpResponse, scenario: Scenario): Scenario {
        val matchingScenario = findBestMatchingScenario(httpResponse)?.scenario ?: return scenario
        return scenario.copy(
            name = matchingScenario.name,
            bindings = matchingScenario.bindings,
            examples = matchingScenario.examples,
            patterns = matchingScenario.patterns,
            fixtures = matchingScenario.fixtures,
            ignoreFailure = matchingScenario.ignoreFailure,
            statusInDescription = matchingScenario.statusInDescription,
            httpResponsePattern = matchingScenario.httpResponsePattern,
        )
    }

    private fun findBestMatchingScenario(httpResponse: HttpResponse): BestEffortMatch? {
        val sameStatus = badRequestResponses[httpResponse.status].orEmpty()
        val otherStatuses = badRequestResponses.filterKeys { it != httpResponse.status }.values.flatten()

        val sameStatusAndContentType = matchByContentType(sameStatus, httpResponse)
        if (sameStatusAndContentType != null) return BestEffortMatch(sameStatusAndContentType)

        val defaultAndContentType = matchByContentType(defaultResponses, httpResponse)
        if (defaultAndContentType != null) return BestEffortMatch(defaultAndContentType, fromDefault = true)

        val sameStatusFirst = sameStatus.firstOrNull()
        if (sameStatusFirst != null) return BestEffortMatch(sameStatusFirst)

        val otherStatusAndContentType = matchByContentType(otherStatuses, httpResponse)
        if (otherStatusAndContentType != null) return BestEffortMatch(otherStatusAndContentType)

        val defaultFirst = defaultResponses.firstOrNull()
        if (defaultFirst != null) return BestEffortMatch(defaultFirst, fromDefault = true)

        val otherStatusFirst = otherStatuses.firstOrNull()
        if (otherStatusFirst != null) return BestEffortMatch(otherStatusFirst)

        return null
    }

    private fun matchByContentType(scenarios: List<Scenario>, httpResponse: HttpResponse): Scenario? {
        return scenarios.firstOrNull { it.matchesContentType(httpResponse) }
    }

    private data class BestEffortMatch(val scenario: Scenario, val fromDefault: Boolean = false)
}
