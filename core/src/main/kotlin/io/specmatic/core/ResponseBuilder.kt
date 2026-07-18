package io.specmatic.core

import io.specmatic.core.pattern.resolvedHop
import io.specmatic.stub.RequestContext

class ResponseBuilder(val scenario: Scenario?) {
    val responseBodyPattern = scenario?.resolver?.withCyclePrevention(
        scenario.httpResponsePattern.body
    ) {
        resolvedHop(scenario.httpResponsePattern.body, it)
    }
    val resolver = scenario?.resolver

    fun build(requestContext: RequestContext): HttpResponse? {
        return scenario?.generateHttpResponse(requestContext)
    }
}
