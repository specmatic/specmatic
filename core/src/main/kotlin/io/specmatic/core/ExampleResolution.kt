package io.specmatic.core

import io.specmatic.core.matchers.MatcherResolutionMode
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pipeline.Pipeline
import io.specmatic.core.value.JSONObjectValue

internal data class ResolvedRequestResponse(val scenario: Scenario, val request: HttpRequest, val response: HttpResponse)
internal fun Scenario.resolveExample(
    request: HttpRequest,
    response: HttpResponse,
    data: JSONObjectValue,
    resolver: Resolver = this.resolver,
): ReturnValue<ResolvedRequestResponse> {
    val resolvedRequest = Pipeline.from(request)
        .then {
            httpRequestPattern.resolveTemplates(
                data = data,
                request = it,
                resolver = resolver,
                resolutionMode = MatcherResolutionMode.LOAD_TIME,
            )
        }
        .run()

    val resolvedResponse = Pipeline.from(response)
        .then {
            httpResponsePattern.resolveTemplates(
                data = data,
                response = it,
                resolver = resolver,
                resolutionMode = MatcherResolutionMode.LOAD_TIME,
            )
        }
        .run()

    return resolvedRequest.combine(resolvedResponse) { resolvedRequestValue, resolvedResponseValue ->
        ResolvedRequestResponse(this, resolvedRequestValue, resolvedResponseValue)
    }
}
