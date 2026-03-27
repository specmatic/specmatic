package io.specmatic.core

class BadRequestOrDefault(private val badRequestResponses: Map<Int, HttpResponsePattern>, private val defaultResponse: HttpResponsePattern?) {
    fun matches(httpResponse: HttpResponse, resolver: Resolver): Result =
        when(httpResponse.status) {
            in badRequestResponses -> badRequestResponses.getValue(httpResponse.status).matchesResponse(httpResponse, resolver)
            else -> defaultResponse?.matchesResponse(httpResponse, resolver)?.partialSuccess("The response matched the default response, but the contract should declare a ${httpResponse.status} response.") ?: Result.Failure(
                "Neither is the status code declared nor is there a default response."
            )
        }

    fun supports(httpStatus: Int): Boolean =
        httpStatus in badRequestResponses || defaultResponse != null
}