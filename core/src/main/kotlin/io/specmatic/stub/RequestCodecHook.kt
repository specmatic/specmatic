package io.specmatic.stub

import io.specmatic.core.value.JSONObjectValue

/**
 * Hook for decoding incoming requests before they are processed by Specmatic.
 *
 * The hook receives the request in Specmatic-style JSON format with only the "http-request" field:
 * ```json
 * {
 *   "http-request": {
 *     "method": "GET",
 *     "path": "/api/endpoint",
 *     "query": { "param": "value" },
 *     "headers": { "header": "value" },
 *     "body": { ... }
 *   }
 * }
 * ```
 *
 * The hook should return the decoded JSON in the same format.
 * If null is returned, the original request is used unchanged.
 */
interface RequestCodecHook {
    /**
     * Decode the incoming request.
     *
     * @param requestJson The request in Specmatic JSON format with "http-request" field
     * @return The decoded request JSON, or null to use the original request unchanged
     */
    fun codecRequest(requestJson: JSONObjectValue): JSONObjectValue?

    /**
     * Decode the incoming request with detailed error information.
     *
     * @param requestJson The request in Specmatic JSON format with "http-request" field
     * @return InterceptorResult containing the decoded request and any errors that occurred
     */
    fun codecRequestWithResult(requestJson: JSONObjectValue): InterceptorResult<JSONObjectValue> {
        val result = codecRequest(requestJson)
        return if (result != null) {
            InterceptorResult.success(result)
        } else {
            InterceptorResult.passthrough()
        }
    }
}
