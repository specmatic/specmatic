package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.log.logger
import io.specmatic.core.value.JSONObjectValue

/**
 * Adapter that converts a ResponseCodecHook into a ResponseInterceptor.
 *
 * This allows hooks that work with JSON format to be integrated into the
 * existing response interceptor chain.
 */
class ResponseCodecHookAdapter(
    private val hook: ResponseCodecHook
) : ResponseInterceptor {
    override fun interceptResponseWithErrors(httpRequest: HttpRequest, httpResponse: HttpResponse): InterceptorResult<HttpResponse> {
        try {
            // Convert HttpRequest and HttpResponse to Specmatic JSON format
            val requestResponseJson = JSONObjectValue(
                mapOf(
                    "http-request" to httpRequest.toJSON(),
                    "http-response" to httpResponse.toJSON()
                )
            )

            // Call the codec hook with result
            val result = hook.codecResponseWithResult(requestResponseJson)

            // If errors occurred, return them
            if (result.errors.isNotEmpty()) {
                return InterceptorResult(null, result.errors)
            }

            // If no value returned (passthrough), use original
            val decodedJson = result.value ?: return InterceptorResult.success(httpResponse)

            // Extract the decoded "http-response" and convert back to HttpResponse
            val decodedResponseJson = decodedJson.jsonObject["http-response"]
                ?: return InterceptorResult.success(httpResponse)

            if (decodedResponseJson !is JSONObjectValue) {
                return InterceptorResult.success(httpResponse)
            }

            val decodedResponse = HttpResponse.fromJSON(decodedResponseJson.jsonObject)
            return InterceptorResult.success(decodedResponse)
        } catch (e: Throwable) {
            // Return error instead of throwing
            val error = CodecError(
                exitCode = -1,
                stdout = "",
                stderr = "Error in response hook adapter: ${e.message}",
                hookType = "response_hook_adapter"
            )
            return InterceptorResult.failure(error)
        }
    }

    override fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse? {
        return interceptResponseWithErrors(httpRequest, httpResponse).value ?: httpResponse
    }
}
