package io.specmatic.stub

import io.specmatic.core.*
import io.specmatic.core.value.JSONObjectValue

data class HttpStubResponse(
    val response: HttpResponse,
    val delayInMilliSeconds: Long? = null,
    val contractPath: String = "",
    val examplePath: String? = null,
    val feature: Feature? = null,
    val scenario: Scenario? = null,
    val mock: HttpStubData? = null,
    val isInternalStubPath: Boolean = false
) {
    val responseBody = response.body

    fun resolveSubstitutions(
        request: HttpRequest,
        originalRequest: HttpRequest,
        data: JSONObjectValue,
    ): HttpStubResponse {
        if(scenario == null)
            return this

        val updatedResponse = scenario.resolveSubstitutions(request, originalRequest, response, data)

        return this.copy(response = updatedResponse)
    }
}