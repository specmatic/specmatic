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
    override fun interceptRequestAndReturnErrors(httpRequest: HttpRequest): InterceptorResult<HttpRequest> {
        try {
            // Convert HttpRequest to Specmatic JSON format with "http-request" field
            val requestJson = JSONObjectValue(
                mapOf("http-request" to httpRequest.toJSON())
            )

            // Call the codec hook with result
            val result = hook.codecRequestWithResult(requestJson)

            // If errors occurred, return them
            if (result.errors.isNotEmpty()) {
                return InterceptorResult(null, result.errors)
            }

            // If no value returned (passthrough), use original
            val decodedJson = result.value ?: return InterceptorResult.success(httpRequest)

            // Extract the decoded "http-request" and convert back to HttpRequest
            val decodedRequestJson = decodedJson.jsonObject["http-request"]
                ?: return InterceptorResult.success(httpRequest)

            if (decodedRequestJson !is JSONObjectValue) {
                return InterceptorResult.success(httpRequest)
            }

            val decodedRequest = requestFromJSON(decodedRequestJson.jsonObject)
            return InterceptorResult.success(decodedRequest)
        } catch (e: Throwable) {
            // Return error instead of throwing
            val error = InterceptorError(
                exitCode = -1,
                stdout = "",
                stderr = "Error in request hook adapter: ${e.message}",
                hookType = "request_hook_adapter"
            )
            return InterceptorResult.failure(error)
        }
    }

    override fun interceptRequest(httpRequest: HttpRequest): HttpRequest? {
        return interceptRequestAndReturnErrors(httpRequest).value ?: httpRequest
    }
}
