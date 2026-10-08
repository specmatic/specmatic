package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.RequestScore.Companion.orEmpty
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.invalidRequestStatuses
import io.specmatic.core.matchers.MatcherResolutionMode
import io.specmatic.core.mostSpecificMatchingBaseUrl
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.core.pipeline.Pipeline
import io.specmatic.mock.ScenarioStub
import java.io.File
import java.net.URI

class ThreadSafeListOfStubs(
    private val httpStubs: MutableList<HttpStubData>,
    private val specToBaseUrlMap: Map<String, String>,
    private val strictMode: Boolean = false,
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig(),
) {
    val size: Int
        get() {
            return httpStubs.size
        }

    fun stubAssociatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): ThreadSafeListOfStubs {
        val baseUrlToListOfStubsMap = baseUrlToListOfStubsMap(defaultBaseUrl).mapKeys { URI(it.key) }
        val resolvedUrls = setOf(baseUrl, defaultBaseUrl).map { it.plus(urlPath) }.map(::URI)

        return resolvedUrls.firstNotNullOfOrNull { resolvedUrl ->
            specmaticConfig.mostSpecificMatchingBaseUrl(
                resolvedUrl,
                baseUrlToListOfStubsMap.keys
            )?.let(baseUrlToListOfStubsMap::get)
        } ?: emptyStubs()
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

    fun remove(element: HttpStubData) {
        synchronized(this) {
            val index = httpStubs.indexOfFirst { it === element }
            if (index >= 0) {
                httpStubs.removeAt(index)
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

    internal data class TransientMatch(val original: HttpStubData, val final: HttpStubData)
    fun matchingTransientStub(httpRequest: HttpRequest): Pair<HttpStubData, List<Pair<Result, HttpStubData>>>? {
        return matchingTransientStubWithOriginal(httpRequest)?.let {
            (match, results) -> match.final to results
        }
    }

    internal fun matchingTransientStubWithOriginal(httpRequest: HttpRequest): Pair<TransientMatch, List<Pair<Result, HttpStubData>>>? {
        val expectedResponseCode = httpRequest.expectedResponseCode()

        val queueMatchResults: List<Pair<Result, HttpStubData>> = matchResults { stubs ->
            stubs.filter {
                hasExpectedResponseCode(it, expectedResponseCode)
            }.filter { it.partial == null }.map {
                Pair(it.matches(httpRequest), it)
            }.plus(partialMatchResults(stubs, httpRequest))
        }

        val preparedMatches = queueMatchResults.map { (result, stubData) ->
            if (result !is Result.Success) return@map Triple(result, stubData, stubData)
            val prepared = substituteThenFillIn(httpRequest, stubData)
            Triple(prepared.first, prepared.second, stubData)
        }

        val preferredMatch = preparedMatches.findLast { (result, preparedStub) ->
            result is Result.Success && preparedStub.hasCompleteAuthoredSecurityRequirement()
        }

        val (_, preparedStub, originalStub) = preferredMatch ?: preparedMatches.findLast { (result, _) ->
            result is Result.Success
        } ?: return null

        return Pair(
            first = TransientMatch(original = originalStub, final = preparedStub),
            second = preparedMatches.map { (result, stubData, _) -> result to stubData },
        )
    }

    fun hasPotentialTransientMatch(httpRequest: HttpRequest): Boolean {
        val expectedResponseCode = httpRequest.expectedResponseCode()
        return synchronized(this) { httpStubs.toList() }.any { stubData ->
            hasExpectedResponseCode(stubData, expectedResponseCode) &&
            runCatching { stubData.matchesRequestPattern(httpRequest).isSuccess() }.getOrDefault(true)
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

    private fun baseUrlToListOfStubsMap(defaultBaseUrl: String): Map<String, ThreadSafeListOfStubs> {
        synchronized(this) {
            return httpStubs.groupBy {
                specToBaseUrlMap[it.contractPath] ?: defaultBaseUrl
            }.mapValues { (_, stubs) ->
                ThreadSafeListOfStubs(stubs as MutableList<HttpStubData>, specToBaseUrlMap, strictMode, specmaticConfig)
            }
        }
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
            Pipeline.from(stubResponse)
                .then { response ->
                    HasValue(response.resolveSubstitutions(httpRequest, originalRequest ?: httpRequest, stubData.data))
                }
                .then { substituted ->
                    stubData.responsePattern.resolveTemplates(stubData.resolver, substituted.response, stubData.data)
                        .ifValue(substituted::withResponse)
                }
                .then { resolved ->
                    val filledIn = stubData.responsePattern.fillInTheBlanks(resolved.response, stubData.resolver)
                    HasValue(stubData.withResponse(filledIn))
                }
                .run()
                .value
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
                val partialResult = requestPattern.resolveTemplates(
                    resolver = resolver,
                    data = stubData.data,
                    request = partial.request,
                    resolutionMode = MatcherResolutionMode.LOAD_TIME,
                ).realise(
                    orFailure = { it.toFailure() },
                    orException = { it.toFailure() },
                    hasValue = { resolvedRequest, _ ->
                        requestPattern.generateExactHttpRequestPatternFrom(resolvedRequest, resolver)
                            .matches(httpRequest, partialResolver, partialResolver)
                    },
                )

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
