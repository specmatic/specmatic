package io.specmatic.stub

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.mock.ScenarioStub

internal class DefaultHttpStubHandler(private val context: HttpStubHandlerContext) : HttpStubHandler.Default {
    private val specmaticConfig = context.specmaticConfigSource.load().config
    private val httpExpectations = HttpExpectations(
        strictMode = context.strictMode,
        specmaticConfig = specmaticConfig,
        specToBaseUrlMap = context.specToBaseUrlMap,
        static = staticHttpStubData(context.rawHttpStubs),
        transient = context.rawHttpStubs.filter { it.stubToken != null }.reversed().toMutableList(),
    )

    override val stubCount: Int
        get() = httpExpectations.stubCount

    override val transientStubCount: Int
        get() = httpExpectations.transientStubCount

    override fun serveStubResponse(
        baseUrl: String,
        urlPath: String,
        defaultBaseUrl: String,
        features: List<Feature>,
        httpRequest: HttpRequest,
    ): StubbedResponseResult {
        return getHttpResponse(
            features = features,
            httpRequest = httpRequest,
            strictMode = context.strictMode,
            specmaticConfig = specmaticConfig,
            httpClientFactory = context.httpClientFactory,
            passThroughTargetBase = context.passThroughTargetBase,
            httpExpectations = httpExpectations.associatedTo(baseUrl, defaultBaseUrl, urlPath),
        )
    }

    override fun addExpectation(stub: ScenarioStub, expectations: List<HttpStubData>) {
        val results = expectations.map {
            Pair(Result.Success(), it.copy(scenarioStub = stub))
        }

        if (stub.stubToken != null) {
            results.forEach { httpExpectations.addDynamicTransient(it, stub) }
        } else {
            results.forEach { httpExpectations.addDynamic(it, stub) }
        }
    }

    override fun removeWithToken(token: String?) {
        httpExpectations.removeWithToken(token)
    }

    private fun staticHttpStubData(rawHttpStubs: List<HttpStubData>): MutableList<HttpStubData> {
        val staticStubs = rawHttpStubs.filter { it.stubToken == null }
        val stubsFromSpecificationExamples: List<HttpStubData> = context.features
            .flatMap { it.loadInlineExamplesAsStub() }
            .mapNotNull { returnValue -> returnValue.withDefault(null) { it } }

        return staticStubs.plus(stubsFromSpecificationExamples).toMutableList()
    }
}
