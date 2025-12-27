package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.stub.report.StubEndpoint

class NotStubbed(override val response: HttpStubResponse, val stubResult: Result) : StubbedResponseResult {
    override fun log(logs: MutableList<StubEndpoint>, httpRequest: HttpRequest) {
        // No op
        // We do not log requests that were not stubbed
    }
}