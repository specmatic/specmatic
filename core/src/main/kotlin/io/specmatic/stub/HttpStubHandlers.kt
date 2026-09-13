package io.specmatic.stub

import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.MockMode
import io.specmatic.core.pattern.ContractException
import io.specmatic.mock.ScenarioStub
import java.io.File

data class HandlerSelection(val handler: HttpStubHandler, val features: List<Feature>)
class HttpStubHandlers(
    specmaticConfigPath: String?,
    private val strictMode: Boolean,
    private val features: List<Feature>,
    private val passThroughTargetBase: String,
    private val rawHttpStubs: List<HttpStubData>,
    private val specmaticConfig: SpecmaticConfig,
    private val httpClientFactory: HttpClientFactory,
    private val specToBaseUrlMap: Map<String, String>,
) {
    private val specmaticConfigSource = SpecmaticConfigSource.fromConfigObject(specmaticConfig, specmaticConfigPath)
    private val mockModeByFeature = features.associate { feature ->
        feature.path to specmaticConfig.getMockMode(File(feature.path))
    }

    private val statefulMockHandler: HttpStubHandler.Stateful? = createStatefulMockHandler()
    private val mockHandler: HttpStubHandler.Default = createStatelessMockHandler()

    val stubCount: Int
        get() = mockHandler.stubCount

    val transientStubCount: Int
        get() = mockHandler.transientStubCount

    fun handlerForRequest(candidateFeatures: List<Feature>, httpRequest: HttpRequest): HandlerSelection {
        val mode = modeForRequest(candidateFeatures, httpRequest)
        return HandlerSelection(
            handler = handlerFor(mode),
            features = featuresForMode(candidateFeatures, mode)
        )
    }

    fun addExpectation(feature: Feature, stub: ScenarioStub, expectations: List<HttpStubData>) {
        val handler = handlerFor(modeForFeature(feature))
        handler.addExpectation(stub, expectations)
    }

    fun removeWithToken(token: String?) {
        mockHandler.removeWithToken(token)
    }


    private fun featuresForMode(candidateFeatures: List<Feature>, mode: MockMode): List<Feature> {
        if (statefulMockHandler == null) return candidateFeatures
        return candidateFeatures.filter { modeForFeature(it) == mode }
    }

    private fun handlerFor(mode: MockMode): HttpStubHandler {
        return when (mode) {
            MockMode.MOCK -> mockHandler
            MockMode.STATEFUL_MOCK -> statefulMockHandler ?: mockHandler
        }
    }

    private fun modeForRequest(candidateFeatures: List<Feature>, httpRequest: HttpRequest): MockMode {
        if (statefulMockHandler == null) return MockMode.MOCK
        val matchingFeature = candidateFeatures.firstOrNull { feature -> featureMatchesRequest(feature, httpRequest) }
        return modeForFeature(matchingFeature ?: candidateFeatures.firstOrNull())
    }

    private fun createStatefulMockHandler(): HttpStubHandler.Stateful? {
        if (features.none { modeForFeature(it) == MockMode.STATEFUL_MOCK }) return null
        return HttpStubHandler.Stateful.create(contextFor(MockMode.STATEFUL_MOCK))
            ?: throw ContractException("Stateful mocking is not supported in Specmatic Open Source")
    }

    private fun createStatelessMockHandler(): HttpStubHandler.Default {
        val context = contextFor(MockMode.MOCK, skipModeFilter = statefulMockHandler == null)
        return DefaultHttpStubHandler(context)
    }

    private fun contextFor(mode: MockMode, skipModeFilter: Boolean = false): HttpStubHandlerContext {
        return HttpStubHandlerContext(
            strictMode = strictMode,
            specToBaseUrlMap = specToBaseUrlMap,
            httpClientFactory = httpClientFactory,
            passThroughTargetBase = passThroughTargetBase,
            specmaticConfigSource = specmaticConfigSource,
            features = if (skipModeFilter) features else features.filter { modeForFeature(it) == mode },
            rawHttpStubs = if (skipModeFilter) rawHttpStubs else rawHttpStubs.filter { modeForRawHttpStub(it) == mode },
        )
    }

    private fun modeForFeature(feature: Feature?): MockMode {
        val feature = feature ?: return MockMode.MOCK
        return mockModeByFeature[feature.path] ?: MockMode.MOCK
    }

    private fun modeForRawHttpStub(stubData: HttpStubData): MockMode {
        return mockModeByFeature[stubData.contractPath] ?: MockMode.MOCK
    }

    companion object {
        fun featureMatchesRequest(feature: Feature, httpRequest: HttpRequest): Boolean {
            if (feature.identifierMatchingScenario(httpRequest) != null) return true
            val expectedStatus = httpRequest.expectedResponseCode()
            return feature.scenarios
                .asSequence()
                .filter { scenario -> scenario.status in setOf(405, 415) && (expectedStatus == null || scenario.status == expectedStatus) }
                .any { scenario -> scenario.requestBelongsToScenarioForExpectedStatus(httpRequest, expectedStatus ?: scenario.status) }
        }
    }
}
