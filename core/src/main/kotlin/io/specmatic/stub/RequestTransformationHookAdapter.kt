package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.requestFromJSON
import io.specmatic.core.value.JSONObjectValue

/**
 * Adapter that converts a RequestTransformationHook into a RequestInterceptor.
 *
 * This allows hooks that work with JSON format to be integrated into the
 * existing request interceptor chain.
 */
class RequestTransformationHookAdapter(
    private val hook: RequestTransformationHook
) : RequestInterceptor {
    override fun interceptRequest(httpRequest: HttpRequest): HttpRequest? {
        try {
            // Convert HttpRequest to Specmatic JSON format with "http-request" field
            val requestJson = JSONObjectValue(
                mapOf("http-request" to httpRequest.toJSON())
            )

            // Call the transformation hook
            val transformedJson = hook.transformRequest(requestJson)

            // If null returned, use original request
            if (transformedJson == null) {
                return httpRequest
            }

            // Extract the transformed "http-request" and convert back to HttpRequest
            val transformedRequestJson = transformedJson.jsonObject["http-request"]
                ?: return httpRequest

            if (transformedRequestJson !is JSONObjectValue) {
                return httpRequest
            }

            return requestFromJSON(transformedRequestJson.jsonObject)
        } catch (e: Throwable) {
            // Log error and return original request to avoid breaking the stub
            io.specmatic.core.log.logger.log(e, "Error in request transformation hook")
            return httpRequest
        }
    }
}
