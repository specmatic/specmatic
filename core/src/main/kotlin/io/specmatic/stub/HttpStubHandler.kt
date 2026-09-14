package io.specmatic.stub

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.mock.ScenarioStub
import java.util.ServiceLoader

data class HttpStubHandlerContext(
    val strictMode: Boolean,
    val features: List<Feature>,
    val passThroughTargetBase: String,
    val rawHttpStubs: List<HttpStubData>,
    val httpClientFactory: HttpClientFactory,
    val specToBaseUrlMap: Map<String, String>,
    val specmaticConfigSource: SpecmaticConfigSource,
)

interface HttpStubHandlerFactory {
    fun stateful(context: HttpStubHandlerContext): HttpStubHandler.Stateful?
}

interface HttpStubHandler {
    fun addExpectation(stub: ScenarioStub, expectations: List<HttpStubData>)
    fun serveStubResponse(baseUrl: String, urlPath: String, defaultBaseUrl: String, features: List<Feature>, httpRequest: HttpRequest): StubbedResponseResult

    interface Default : HttpStubHandler {
        val stubCount: Int
        val transientStubCount: Int
        fun removeWithToken(token: String?)
    }

    interface Stateful : HttpStubHandler {
        companion object {
            fun create(context: HttpStubHandlerContext): Stateful? {
                val services = ServiceLoader.load(HttpStubHandlerFactory::class.java).toList()
                return services.firstNotNullOfOrNull { it.stateful(context) }
            }
        }
    }
}
