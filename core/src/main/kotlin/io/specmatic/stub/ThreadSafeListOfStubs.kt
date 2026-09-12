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

internal data class StubBaseUrlAssociation(
    val baseUrl: String,
    val defaultBaseUrl: String,
    val urlPath: String,
)

class ThreadSafeListOfStubs(
    private val httpStubs: MutableList<HttpStubData>,
    private val specToBaseUrlMap: Map<String, String>,
    private val strictMode: Boolean = false,
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
) {
    val size: Int
        get() {
            synchronized(this) {
                return httpStubs.size
            }
        }

    fun stubAssociatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): ThreadSafeListOfStubs {
        synchronized(this) {
            val associatedStubs = stubsAssociatedTo(baseUrl, defaultBaseUrl, urlPath)
            if (associatedStubs.isEmpty()) return emptyStubs()
            return ThreadSafeListOfStubs(associatedStubs.toMutableList(), specToBaseUrlMap, strictMode, specmaticConfig)
        }
    }

    private fun matchResults(fn: (List<HttpStubData>) -> List<Pair<Result, HttpStubData>>): List<Pair<Result, HttpStubData>> {
        synchronized(this) {
            return fn(httpStubs.toList())
        }
    }

    fun addToStub(result: Pair<Result, HttpStubData?>, stub: ScenarioStub) {
        synchronized(this) {
            result.second.let {
                if(it != null)
                    httpStubs.add(0, it.copy(scenarioStub = stub))
            }
        }
    }

    fun removeWithToken(token: String?) {
        synchronized(this) {
            httpStubs.mapIndexed { index, httpStubData ->
                if (httpStubData.stubToken == token) index else null
            }.filterNotNull().reversed().map { index ->
                httpStubs.removeAt(index)
            }
        }
    }

    fun matchingTransientStub(httpRequest: HttpRequest): Pair<HttpStubData, List<Pair<Result, HttpStubData>>>? {
        return matchingTransientStub(httpRequest, null)
    }

    internal fun matchingTransientStub(
        httpRequest: HttpRequest,
        association: StubBaseUrlAssociation?,
    ): Pair<HttpStubData, List<Pair<Result, HttpStubData>>>? {
        synchronized(this) {
            val candidates = if (association == null) {
                httpStubs.toList()
            } else {
                stubsAssociatedTo(association.baseUrl, association.defaultBaseUrl, association.urlPath)
            }
            val match = findMatchingTransientStub(httpRequest, candidates) ?: return null
            if (match.first.utilize()) {
                httpStubs.remove(match.first)
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

    private fun stubsAssociatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): List<HttpStubData> {
        val groupedByBaseUrl = httpStubs.groupBy {
            specToBaseUrlMap[it.contractPath] ?: defaultBaseUrl
        }.mapKeys { URI(it.key) }
        val resolvedUrls = setOf(baseUrl, defaultBaseUrl).map { it.plus(urlPath) }.map(::URI)

        return resolvedUrls.firstNotNullOfOrNull { resolvedUrl ->
            specmaticConfig.mostSpecificMatchingBaseUrl(
                resolvedUrl,
                groupedByBaseUrl.keys
            )?.let(groupedByBaseUrl::get)
        } ?: emptyList()
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

    private fun emptyStubs(): ThreadSafeListOfStubs {
        return ThreadSafeListOfStubs(mutableListOf(), specToBaseUrlMap, strictMode, specmaticConfig)
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
