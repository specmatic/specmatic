package io.specmatic.core.jsonoperator

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Scenario
import io.specmatic.core.pattern.HasFailure
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.unwrapOrReturn
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value

data class RequestResponseOperator(
    private val requestOperator: RequestOperator,
    private val responseOperator: ResponseOperator,
) : JsonPointerOperator<Value, OperatorCapability.Immutable> {
    override fun get(segments: List<PathSegment>): ReturnValue<Optional<RootMutableJsonOperator<out Value>>> {
        val currentSegment = segments.takeNextAs<PathSegment.Key>().unwrapOrReturn { return it.cast() }
        val tailSegments = segments.drop(1)
        return when (currentSegment.key) {
            "request" -> requestOperator.get(tailSegments)
            "response" -> responseOperator.get(tailSegments)
            else -> HasFailure("Invalid key '${currentSegment.key} must be one of request, response")
        }
    }

    override fun finalize(): ReturnValue<Value> {
        val request = requestOperator.finalize().unwrapOrReturn { return it.cast() }
        val response = responseOperator.finalize().unwrapOrReturn { return it.cast() }
        return HasValue(JSONObjectValue(mapOf("request" to request.toJSON(), "response" to response.toJSON())))
    }

    companion object {
        fun from(request: HttpRequest, response: HttpResponse, scenario: Scenario): RequestResponseOperator {
            return RequestResponseOperator(
                requestOperator = RequestOperator.from(request, scenario.httpRequestPattern, scenario.resolver),
                responseOperator = ResponseOperator.from(response),
            )
        }

        fun from(request: HttpRequest, scenario: Scenario): RequestResponseOperator {
            return RequestResponseOperator(
                requestOperator = RequestOperator.from(request, scenario.httpRequestPattern, scenario.resolver),
                responseOperator = ResponseOperator.from(HttpResponse()),
            )
        }
    }
}
