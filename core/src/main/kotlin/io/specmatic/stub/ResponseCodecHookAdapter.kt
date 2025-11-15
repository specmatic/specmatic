package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
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
    override fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse? {
        try {
            io.specmatic.core.log.logger.log("  Response codec hook: Decoding response for ${httpRequest.method} ${httpRequest.path} (status ${httpResponse.status})")

            // Convert HttpRequest and HttpResponse to Specmatic JSON format
            val requestResponseJson = JSONObjectValue(
                mapOf(
                    "http-request" to httpRequest.toJSON(),
                    "http-response" to httpResponse.toJSON()
                )
            )

            // Call the codec hook
            val decodedJson = hook.codecResponse(requestResponseJson)

            // If null returned, use original response
            if (decodedJson == null) {
                io.specmatic.core.log.logger.log("  Response codec hook returned null, using original response")
                return httpResponse
            }

            // Extract the decoded "http-response" and convert back to HttpResponse
            val decodedResponseJson = decodedJson.jsonObject["http-response"]
                ?: return httpResponse

            if (decodedResponseJson !is JSONObjectValue) {
                return httpResponse
            }

            val decodedResponse = HttpResponse.fromJSON(decodedResponseJson.jsonObject)
            io.specmatic.core.log.logger.log("  Response codec hook: Successfully decoded response")
            return decodedResponse
        } catch (e: Throwable) {
            // Log error and return original response to avoid breaking the stub
            io.specmatic.core.log.logger.log(e, "  Error in response codec hook")
            return httpResponse
        }
    }
}
