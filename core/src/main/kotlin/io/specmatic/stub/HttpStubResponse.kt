package io.specmatic.stub

import io.specmatic.core.*
import io.specmatic.core.value.JSONObjectValue

data class HttpStubResponse(
    val response: HttpResponse,
    val delayInMilliSeconds: Long? = null,
    val contractPath: String = "",
    val exampleName: String? = null,
    val examplePath: String? = null,
    val feature: Feature? = null,
    val scenario: Scenario? = null,
    val mock: HttpStubData? = null,
    val isInternalStubPath: Boolean = false,
    val strictMode: Boolean = false
) {
    val responseBody = response.body

    /**
     * Mock templates bridge symbolic examples and contract-constrained responses. Audit substitution
     * and property drops with Scenario response resolution and Pattern constraint checking: they must
     * agree on the validity of the resolved response. Drop behavior belongs to this mock-side boundary.
     */
    fun resolveSubstitutions(
        request: HttpRequest,
        originalRequest: HttpRequest,
        data: JSONObjectValue,
    ): HttpStubResponse {
        if(scenario == null)
            return this

        val updatedResponse = scenario.resolveSubstitutions(
            data = data,
            request = request,
            response = response,
            originalRequest = originalRequest,
            strictMode = strictMode,
        )

        return this.copy(response = updatedResponse)
    }

    fun withResponse(response: HttpResponse): HttpStubResponse {
        return this.copy(response = response)
    }
}