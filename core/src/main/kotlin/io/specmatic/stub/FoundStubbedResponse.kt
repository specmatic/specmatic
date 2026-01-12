package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.stub.report.StubEndpoint

class FoundStubbedResponse(override val response: HttpStubResponse) : StubbedResponseResult {
    override fun log(logs: MutableList<StubEndpoint>, httpRequest: HttpRequest) {
        logs.add(
            StubEndpoint(
                response.scenario?.path ?: httpRequest.path,
                httpRequest.method,
                response.response.status,
                response.scenario?.requestContentType ?: httpRequest.contentType(),
                response.feature?.sourceProvider,
                response.feature?.sourceRepository,
                response.feature?.sourceRepositoryBranch,
                response.feature?.specification,
                response.scenario?.protocol ?: SpecmaticProtocol.HTTP,
                response.scenario?.specType ?: SpecType.OPENAPI
            )
        )
    }
}