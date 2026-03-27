package io.specmatic.core

import io.ktor.http.HttpStatusCode

class BadRequestOrDefault(private val badRequestResponses: Map<Int, Scenario>, private val defaultResponse: Scenario?) {
    fun matches(httpResponse: HttpResponse, resolver: Resolver): Result =
        when(httpResponse.status) {
            in badRequestResponses -> badRequestResponses.getValue(httpResponse.status).httpResponsePattern.matchesResponse(httpResponse, resolver)
            else -> defaultResponse?.httpResponsePattern?.matchesResponse(httpResponse, resolver)?.partialSuccess("The response matched the default response, but the contract should declare a ${httpResponse.status} response.") ?: Result.Failure(
                "Neither is the status code declared nor is there a default response."
            )
        }

    fun supports(httpStatus: Int): Boolean =
        httpStatus in badRequestResponses || defaultResponse != null

    private fun getBadRequestScenarioOrFirst(): Scenario? {
        if (badRequestResponses.containsKey(HttpStatusCode.BadRequest.value)) return badRequestResponses.getValue(HttpStatusCode.BadRequest.value)
        return badRequestResponses.values.firstOrNull() ?: defaultResponse
    }

    companion object {
        fun BadRequestOrDefault?.updateScenarioWithBadRequestPattern(successScenario: Scenario): Scenario {
            return this?.getBadRequestScenarioOrFirst() ?: successScenario.copy(
                statusInDescription = HttpStatusCode.BadRequest.value.toString(),
                httpResponsePattern = successScenario.httpResponsePattern.copy(status = HttpStatusCode.BadRequest.value),
            )
        }
    }
}
