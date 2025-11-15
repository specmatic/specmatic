package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.requestFromJSON
import io.specmatic.core.value.JSONObjectValue

/**
 * Adapter that converts a RequestCodecHook into a RequestInterceptor.
 *
 * This allows hooks that work with JSON format to be integrated into the
 * existing request interceptor chain.
 */
class RequestCodecHookAdapter(
    private val hook: RequestCodecHook
) : RequestInterceptor {
    override fun interceptRequest(httpRequest: HttpRequest): HttpRequest? {
        try {
            // Convert HttpRequest to Specmatic JSON format with "http-request" field
            val requestJson = JSONObjectValue(
                mapOf("http-request" to httpRequest.toJSON())
            )

            // Call the codec hook
            val decodedJson = hook.codecRequest(requestJson)

            // If null returned, use original request
            if (decodedJson == null) {
                return httpRequest
            }

            // Extract the decoded "http-request" and convert back to HttpRequest
            val decodedRequestJson = decodedJson.jsonObject["http-request"]
                ?: return httpRequest

            if (decodedRequestJson !is JSONObjectValue) {
                return httpRequest
            }

            return requestFromJSON(decodedRequestJson.jsonObject)
        } catch (e: Throwable) {
            // Log error and return original request to avoid breaking the stub
            io.specmatic.core.log.logger.log(e, "Error in request codec hook")
            return httpRequest
        }
    }
}
