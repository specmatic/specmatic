package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.mock.ScenarioStub

class HttpExpectations private constructor (
    private val static: ThreadSafeListOfStubs,
    private val transient: ThreadSafeListOfStubs,
    private val dynamic: ThreadSafeListOfStubs
) {
    constructor(
        static: MutableList<HttpStubData>,
        transient: MutableList<HttpStubData> = mutableListOf(),
        specToBaseUrlMap: Map<String, String> = emptyMap()
    ) : this(
        static = ThreadSafeListOfStubs(static, specToBaseUrlMap),
        transient = ThreadSafeListOfStubs(transient, specToBaseUrlMap),
        dynamic = ThreadSafeListOfStubs(mutableListOf(), specToBaseUrlMap)
    )

    val stubCount: Int get() { return static.size }
    val transientStubCount: Int get() { return transient.size }

    fun utilizeMock(httpStubData: HttpStubData) {
        val shouldBeRemovedFromTransient = httpStubData.utilize()
        if (!shouldBeRemovedFromTransient) return
        transient.remove(httpStubData)
    }

    fun removeWithToken(token: String?) {
        transient.removeWithToken(token)
    }

    fun addDynamicTransient(expectation: Pair<Result.Success, HttpStubData>, stub: ScenarioStub) {
        return transient.addToStub(expectation, stub)
    }

    fun addDynamic(expectation: Pair<Result.Success, HttpStubData>, stub: ScenarioStub) {
        return dynamic.addToStub(expectation, stub)
    }

    fun associatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): HttpExpectations {
        return HttpExpectations(
            static.stubAssociatedTo(baseUrl, defaultBaseUrl, urlPath),
            transient.stubAssociatedTo(baseUrl, defaultBaseUrl, urlPath),
            dynamic.stubAssociatedTo(baseUrl, defaultBaseUrl, urlPath))
    }

    fun matchingStub(httpRequest: HttpRequest): Pair<HttpStubData?, List<Pair<Result, HttpStubData>>> {
        val transientMatch = transient.matchingTransientStub(httpRequest)
        if(transientMatch != null)
            return transientMatch

        val dynamicMatch = dynamic.matchingDynamicStub(httpRequest)
        if(dynamicMatch.first != null)
            return dynamicMatch

        val staticMatch = static.matchingStaticStub(httpRequest)
        if(staticMatch.first != null)
            return staticMatch

        val failures = dynamicMatch.second + staticMatch.second

        return Pair(null, failures)
    }
}
