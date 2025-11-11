package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.JSONObjectValue

/**
 * Adapter that converts a ResponseTransformationHook into a ResponseInterceptor.
 *
 * This allows hooks that work with JSON format to be integrated into the
 * existing response interceptor chain.
 */
class ResponseTransformationHookAdapter(
    private val hook: ResponseTransformationHook
) : ResponseInterceptor {
    override fun interceptResponse(httpRequest: HttpRequest, httpResponse: HttpResponse): HttpResponse? {
        try {
            // Convert HttpRequest and HttpResponse to Specmatic JSON format
            val requestResponseJson = JSONObjectValue(
                mapOf(
                    "http-request" to httpRequest.toJSON(),
                    "http-response" to httpResponse.toJSON()
                )
            )

            // Call the transformation hook
            val transformedJson = hook.transformResponse(requestResponseJson)

            // If null returned, use original response
            if (transformedJson == null) {
                return httpResponse
            }

            // Extract the transformed "http-response" and convert back to HttpResponse
            val transformedResponseJson = transformedJson.jsonObject["http-response"]
                ?: return httpResponse

            if (transformedResponseJson !is JSONObjectValue) {
                return httpResponse
            }

            return HttpResponse.fromJSON(transformedResponseJson.jsonObject)
        } catch (e: Throwable) {
            // Log error and return original response to avoid breaking the stub
            io.specmatic.core.log.logger.log(e, "Error in response transformation hook")
            return httpResponse
        }
    }
}
