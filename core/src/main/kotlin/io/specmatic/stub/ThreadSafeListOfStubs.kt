package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.RequestScore.Companion.orEmpty
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.invalidRequestStatuses
import io.specmatic.core.mostSpecificMatchingBaseUrl
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.mock.ScenarioStub
import java.io.File
import java.net.URI

private sealed interface StubFilter {
    data object All : StubFilter
    data class Associated(
        val baseUrl: String,
        val defaultBaseUrl: String,
        val urlPath: String,
    ) : StubFilter
}

class ThreadSafeListOfStubs private constructor(
    private val httpStubs: MutableList<HttpStubData>,
    private val specToBaseUrlMap: Map<String, String>,
    private val strictMode: Boolean,
    private val specmaticConfig: SpecmaticConfig,
    private val filter: StubFilter,
) {
    constructor(
        httpStubs: MutableList<HttpStubData>,
        specToBaseUrlMap: Map<String, String>,
        strictMode: Boolean = false,
        specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
    ) : this(httpStubs, specToBaseUrlMap, strictMode, specmaticConfig, StubFilter.All)

    val size: Int
        get() {
            synchronized(httpStubs) {
                return visibleStubsLocked().size
            }
        }

    fun stubAssociatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): ThreadSafeListOfStubs {
        return ThreadSafeListOfStubs(
            httpStubs = httpStubs,
            specToBaseUrlMap = specToBaseUrlMap,
            strictMode = strictMode,
            specmaticConfig = specmaticConfig,
            filter = StubFilter.Associated(baseUrl, defaultBaseUrl, urlPath),
        )
    }

    private fun matchResults(fn: (List<HttpStubData>) -> List<Pair<Result, HttpStubData>>): List<Pair<Result, HttpStubData>> {
        val snapshot = synchronized(httpStubs) {
            visibleStubsLocked()
        }
        return fn(snapshot)
    }

    fun addToStub(result: Pair<Result, HttpStubData?>, stub: ScenarioStub) {
        synchronized(httpStubs) {
            result.second.let {
                if(it != null)
                    httpStubs.add(0, it.copy(scenarioStub = stub))
            }
        }
    }

    fun removeWithToken(token: String?) {
        synchronized(httpStubs) {
            httpStubs.mapIndexed { index, httpStubData ->
                if (httpStubData.stubToken == token) index else null
            }.filterNotNull().reversed().map { index ->
                httpStubs.removeAt(index)
            }
        }
    }

    fun matchingTransientStub(httpRequest: HttpRequest): Pair<HttpStubData, List<Pair<Result, HttpStubData>>>? {
        synchronized(httpStubs) {
            val match = findMatchingTransientStub(httpRequest, visibleStubsLocked()) ?: return null
            val selectedStub = match.first
            if (selectedStub.utilize()) {
                val index = httpStubs.indexOfFirst { candidate -> candidate === selectedStub }
                if (index >= 0) httpStubs.removeAt(index)
            }
            return match
        }
    }

    private fun findMatchingTransientStub(
        httpRequest: HttpRequest,
        stubs: List<HttpStubData>,
    ): Pair<HttpStubData, List<Pair<Result, HttpStubData>>>? {
        val expectedResponseCode = httpRequest.expectedResponseCode()
        val queueMatchResults = stubs.filter {
            hasExpectedResponseCode(it, expectedResponseCode)
        }.map {
            Pair(it.matches(httpRequest), it)
        }

        val preferredMatch = queueMatchResults.findLast { (result, stubData) ->
            result is Result.Success && stubData.hasCompleteAuthoredSecurityRequirement()
        }
        val (_, queueMock) = preferredMatch ?: queueMatchResults.findLast { (result, _) ->
            result is Result.Success
        } ?: return null

        return Pair(queueMock, queueMatchResults)
    }

    private fun visibleStubsLocked(): List<HttpStubData> {
        return when (val currentFilter = filter) {
            StubFilter.All -> httpStubs.toList()
            is StubFilter.Associated -> {
                val groupedByBaseUrl = httpStubs.groupBy {
                    specToBaseUrlMap[it.contractPath] ?: currentFilter.defaultBaseUrl
                }.mapKeys { URI(it.key) }
                val resolvedUrls = setOf(currentFilter.baseUrl, currentFilter.defaultBaseUrl)
                    .map { it.plus(currentFilter.urlPath) }
                    .map(::URI)
                val selectedBaseUrl = resolvedUrls.firstNotNullOfOrNull { resolvedUrl ->
                    specmaticConfig.mostSpecificMatchingBaseUrl(resolvedUrl, groupedByBaseUrl.keys)
                }
                selectedBaseUrl?.let(groupedByBaseUrl::get).orEmpty()
            }
        }
    }

    private fun hasExpectedResponseCode(httpStubData: HttpStubData, expectedResponseCode: Int?): Boolean {
        expectedResponseCode ?: return true
        return httpStubData.responsePattern.status == expectedResponseCode
    }

    fun matchingStaticStub(httpRequest: HttpRequest): Pair<HttpStubData?, List<Pair<Result, HttpStubData>>> {
        val expectedResponseCode = httpRequest.expectedResponseCode()

        val listMatchResults: List<Pair<Result, HttpStubData>> = matchResults { httpStubData ->
            httpStubData.filter {
                hasExpectedResponseCode(it, expectedResponseCode)
            }.filter { it.partial == null }.map {
                Pair(it.matches(httpRequest), it)
            }.plus(partialMatchResults(httpStubData, httpRequest))
        }

        val mocks = listMatchResults.map { (result, stubData) ->
            if (result !is Result.Success) return@map Pair(result, stubData)
            substituteThenFillIn(httpRequest, stubData)
        }

        val successfulMatches = mocks.filter { (result, _) -> result is Result.Success }
        val grouped = successfulMatches.groupBy { (_, stubData) ->
            stubData.stubType
        }

        val exactMatches = grouped[StubType.Exact]
            .orEmpty()
            .sortedWith(comparator = compareBy(nullsLast()) { it.second.resolveOriginalRequest()?.generality })
        val exactMatch = exactMatches.find { (_, stubData) ->
            stubData.hasCompleteAuthoredSecurityRequirement()
        } ?: exactMatches.firstOrNull()

        if(exactMatch != null)
            return Pair(exactMatch.second, listMatchResults)

        val partials = grouped[StubType.Partial].orEmpty().map { it.second }
        val preferredPartials = partials.filter { it.hasCompleteAuthoredSecurityRequirement() }
        val partialMatch = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(preferredPartials.ifEmpty { partials })

        if(partialMatch != null)
            return Pair(partialMatch, listMatchResults)

        return Pair(null, listMatchResults)
    }

    fun matchingDynamicStub(httpRequest: HttpRequest): Pair<HttpStubData?, List<Pair<Result, HttpStubData>>> {
        val expectedResponseCode = httpRequest.expectedResponseCode()

        val listMatchResults: List<Pair<Result, HttpStubData>> = matchResults { httpStubData ->
             httpStubData.filter {
                 hasExpectedResponseCode(it, expectedResponseCode)
             }.filter { it.partial == null }.map {
                 Pair(it.matches(httpRequest), it)
             }.plus(partialMatchResults(httpStubData, httpRequest))
        }

        val mocks = listMatchResults.map { (result, stubData) ->
            if (result !is Result.Success) return@map Pair(result, stubData)
            substituteThenFillIn(httpRequest, stubData)
        }

        val mock = mocks.find { (result, stubData) ->
            result is Result.Success && stubData.hasCompleteAuthoredSecurityRequirement()
        } ?: mocks.find { (result, _) -> result is Result.Success }

        return Pair(mock?.second, listMatchResults)
    }

    private fun substituteThenFillIn(httpRequest: HttpRequest, stubData: HttpStubData): Pair<Result, HttpStubData> {
        val originalRequest = stubData.resolveOriginalRequest()
        val strictMode = stubData.feature?.path?.let(::File)?.let(specmaticConfig::getStubStrictMode) ?: strictMode
        val stubResponse = HttpStubResponse(
            stubData.partial?.response ?: stubData.response,
            stubData.delayInMilliseconds,
            stubData.contractPath,
            feature = stubData.feature,
            scenario = stubData.scenario,
            strictMode = strictMode
        )

        return runCatching {
            val substituted = stubResponse.resolveSubstitutions(httpRequest, originalRequest ?: httpRequest, stubData.data)
            val substitutedResponse = stubData.copy(response = substituted.response)
            stubData.copy(response = stubData.responsePattern.fillInTheBlanks(substitutedResponse.response, stubData.resolver))
        }.map { Result.Success() to it }.getOrElse { e ->
            when {
                e is ContractException && isMissingData(e) -> Pair(e.failure(), stubData)
                else -> throw e
            }
        }
    }

    private fun partialMatchResults(
        httpStubData: List<HttpStubData>,
        httpRequest: HttpRequest
    ): List<Pair<Result, HttpStubData>> {
        val expectedResponseCode = httpRequest.expectedResponseCode()

        return httpStubData.mapNotNull { it.partial?.let { partial -> it to partial } }
            .filter {
                hasExpectedResponseCode(it.first, expectedResponseCode)
            }.map { (stubData, partial) ->
                val (requestPattern, _, resolver) = stubData
                val partialResolver = resolver.withUnexpectedKeyCheck(IgnoreUnexpectedKeys)
                val partialResult = requestPattern.generateExactHttpRequestPatternFrom(partial.request, resolver)
                    .matches(httpRequest, partialResolver, partialResolver)

                if (!partialResult.isSuccess()) return@map partialResult to stubData
                if (partial.response.status in invalidRequestStatuses) return@map partialResult to stubData
                Pair(stubData.matches(httpRequest), stubData)
            }
    }

    companion object {
        internal fun getPartialBySpecificityAndGenerality(partials: List<HttpStubData>): HttpStubData? {
            if (partials.isEmpty()) return null
            val highestSpecificity = partials.maxOf { it.resolveOriginalRequest()?.specificity.orEmpty() }
            return partials.asSequence()
                .filter { (it.resolveOriginalRequest()?.specificity.orEmpty()) == highestSpecificity }
                .minByOrNull { it.resolveOriginalRequest()?.generality.orEmpty() }
        }
    }
}
