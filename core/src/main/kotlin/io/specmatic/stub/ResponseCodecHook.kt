package io.specmatic.stub

import io.specmatic.core.value.JSONObjectValue

/**
 * Hook for decoding responses before they are processed by Specmatic.
 *
 * The hook receives both the request and response in Specmatic-style JSON format:
 * ```json
 * {
 *   "http-request": {
 *     "method": "GET",
 *     "path": "/api/endpoint",
 *     "query": { "param": "value" },
 *     "headers": { "header": "value" },
 *     "body": { ... }
 *   },
 *   "http-response": {
 *     "status": 200,
 *     "headers": { "header": "value" },
 *     "body": { ... }
 *   }
 * }
 * ```
 *
 * The hook should return the decoded JSON in the same format.
 * If null is returned, the original response is used unchanged.
 */
interface ResponseCodecHook {
    /**
     * Decode the response.
     *
     * @param requestResponseJson The request and response in Specmatic JSON format
     *                           with "http-request" and "http-response" fields
     * @return The decoded request/response JSON, or null to use the original response unchanged
     */
    fun codecResponse(requestResponseJson: JSONObjectValue): JSONObjectValue?
}
