package io.specmatic.stub

import io.specmatic.core.value.JSONObjectValue

/**
 * Hook for transforming incoming requests to the stub before they are processed.
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
 * The hook should return the transformed JSON in the same format.
 * If null is returned, the original request is used unchanged.
 */
interface RequestTransformationHook {
    /**
     * Transform the incoming request.
     *
     * @param requestJson The request in Specmatic JSON format with "http-request" field
     * @return The transformed request JSON, or null to use the original request unchanged
     */
    fun transformRequest(requestJson: JSONObjectValue): JSONObjectValue?
}
