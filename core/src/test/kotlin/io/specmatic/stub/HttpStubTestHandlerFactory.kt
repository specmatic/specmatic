package io.specmatic.stub

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub

@Suppress("unused")
class HttpStubTestHandlerFactory : HttpStubHandlerFactory {
    override fun stateful(context: HttpStubHandlerContext): HttpStubHandler.Stateful {
        return HttpStubStatefulTestHandler(context)
    }
}

private class HttpStubStatefulTestHandler(private val context: HttpStubHandlerContext) : HttpStubHandler.Stateful {
    private val dynamicExpectations = mutableListOf<ScenarioStub>()

    override fun serveStubResponse(
        baseUrl: String,
        urlPath: String,
        defaultBaseUrl: String,
        features: List<Feature>,
        httpRequest: HttpRequest,
    ): StubbedResponseResult {
        val response = dynamicExpectations
            .firstOrNull { it.request.method == httpRequest.method && it.request.path == httpRequest.path }
            ?.response
            ?: HttpResponse(
                status = 200,
                headers = mapOf(
                    "X-Test-SPI" to "true",
                    "X-Test-SPI-Features" to features.size.toString(),
                    "X-Test-SPI-Raw-Stubs" to context.rawHttpStubs.size.toString(),
                ),
                body = StringValue("served by SPI"),
            )

        return FoundStubbedResponse(
            HttpStubResponse(
                response = response,
                feature = features.firstOrNull(),
                contractPath = features.firstOrNull()?.path.orEmpty(),
            )
        )
    }

    override fun addExpectation(stub: ScenarioStub, expectations: List<HttpStubData>) {
        dynamicExpectations.add(stub)
    }
}
