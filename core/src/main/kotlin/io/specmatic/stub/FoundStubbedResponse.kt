package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.stub.report.StubEndpoint

class FoundStubbedResponse(override val response: HttpStubResponse) : StubbedResponseResult {
    override fun log(logs: MutableList<StubEndpoint>, httpRequest: HttpRequest) {
        logs.add(
            StubEndpoint(
                response.scenario?.name,
                response.scenario?.path ?: httpRequest.path,
                httpRequest.method,
                response.response.status,
                response.scenario?.sourceProvider ?: response.feature?.sourceProvider,
                response.scenario?.sourceRepository ?: response.feature?.sourceRepository,
                response.scenario?.sourceRepositoryBranch ?: response.feature?.sourceRepositoryBranch,
                response.scenario?.specification ?: response.feature?.specification,
                response.scenario?.serviceType ?: response.feature?.serviceType ?: "HTTP",
            ),
        )
    }
}