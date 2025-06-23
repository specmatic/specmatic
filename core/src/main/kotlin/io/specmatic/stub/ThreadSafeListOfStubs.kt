package io.specmatic.stub

import io.specmatic.core.HttpRequest
import io.specmatic.core.KeyCheck
import io.specmatic.core.Result
import io.specmatic.core.invalidRequestStatuses
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.IgnoreUnexpectedKeys
import io.specmatic.mock.ScenarioStub
import java.net.URI

class ThreadSafeListOfStubs(
    private val httpStubs: MutableList<HttpStubData>,
    private val specToBaseUrlMap: Map<String, String>
) {
    val size: Int
        get() {
            return httpStubs.size
        }

    fun stubAssociatedTo(baseUrl: String, defaultBaseUrl: String, urlPath: String): ThreadSafeListOfStubs {
        val baseUrlToListOfStubsMap = baseUrlToListOfStubsMap(defaultBaseUrl).mapKeys { URI(it.key) }
        val resolvedUrls = setOf(baseUrl, defaultBaseUrl).map { it.plus(urlPath) }.map(::URI)

        return resolvedUrls.firstNotNullOfOrNull { resolvedUrl ->
            baseUrlToListOfStubsMap.entries.firstOrNull { (stubBaseUrl, _) ->
                isSameBaseIgnoringHost(resolvedUrl, stubBaseUrl)
            }?.value
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
                    httpStubs.add(0, it.copy(delayInMilliseconds = stub.delayInMilliseconds, stubToken = stub.stubToken))
            }
        }
    }

    fun remove(element: HttpStubData) {
        synchronized(this) {
            httpStubs.remove(element)
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
        val expectedResponseCode = httpRequest.expectedResponseCode()

        val queueMatchResults: List<Pair<Result, HttpStubData>> = matchResults { stubs ->
            stubs.filter {
                hasExpectedResponseCode(it, expectedResponseCode)
            }.map {
                Pair(it.matches(httpRequest), it)
            }
        }

        val (_, queueMock) = queueMatchResults.findLast { (result, _) ->
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

            val response =
                if (stubData.partial == null)
                    stubData.response
                else
                    stubData.responsePattern.fillInTheBlanks(stubData.partial.response, stubData.resolver)

            val stubResponse = HttpStubResponse(
                response,
                stubData.delayInMilliseconds,
                stubData.contractPath,
                feature = stubData.feature,
                scenario = stubData.scenario
            )

            try {
                val originalRequest = stubData.resolveOriginalRequest()

                stubResponse.resolveSubstitutions(
                    httpRequest,
                    originalRequest ?: httpRequest,
                    stubData.data
                )

                result to stubData.copy(response = response)
            } catch (e: ContractException) {
                if (isMissingData(e)) Pair(e.failure(), stubData)
                else throw e
            }
        }

        val successfulMatches = mocks.filter { (result, _) -> result is Result.Success }
        val grouped = successfulMatches.groupBy { (_, stubData) ->
            stubData.stubType
        }

        val exactMatch = grouped[StubType.Exact].orEmpty().sortedBy {
            it.second.resolveOriginalRequest()?.generality ?: Int.MAX_VALUE
        }.find { (result, _) -> result is Result.Success }

        if(exactMatch != null)
            return Pair(exactMatch.second, listMatchResults)

        val partialMatch = ThreadSafeListOfStubs.getPartialBySpecificityAndGenerality(grouped[StubType.Partial].orEmpty().map { it.second })

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

        val mock = listMatchResults.map { (result, stubData) ->
            if (result !is Result.Success) return@map Pair(result, stubData)

            val response = if (stubData.partial == null) stubData.response
            else stubData.responsePattern.fillInTheBlanks(stubData.partial.response, stubData.resolver)

            val stubResponse = HttpStubResponse(
                response,
                stubData.delayInMilliseconds,
                stubData.contractPath,
                feature = stubData.feature,
                scenario = stubData.scenario
            )

            try {
                val originalRequest = stubData.resolveOriginalRequest()

                stubResponse.resolveSubstitutions(
                    httpRequest,
                    originalRequest ?: httpRequest,
                    stubData.data
                )

                result to stubData.copy(response = response)
            } catch (e: ContractException) {
                if (isMissingData(e)) Pair(e.failure(), stubData)
                else throw e
            }
        }.find { (result, _) -> result is Result.Success }

        return Pair(mock?.second, listMatchResults)
    }

    private fun baseUrlToListOfStubsMap(defaultBaseUrl: String): Map<String, ThreadSafeListOfStubs> {
        synchronized(this) {
            return httpStubs.groupBy {
                specToBaseUrlMap[it.contractPath] ?: defaultBaseUrl
            }.mapValues { (_, stubs) ->
                ThreadSafeListOfStubs(stubs as MutableList<HttpStubData>, specToBaseUrlMap)
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

                val partialResolver = resolver.copy(
                    findKeyErrorCheck = KeyCheck(unexpectedKeyCheck = IgnoreUnexpectedKeys)
                )

                val partialResult = requestPattern.generate(partial.request, resolver)
                    .matches(httpRequest, partialResolver, partialResolver)

                if (!partialResult.isSuccess()) return@map partialResult to stubData
                if (partial.response.status in invalidRequestStatuses) return@map partialResult to stubData
                Pair(stubData.matches(httpRequest), stubData)
            }
    }

    private fun emptyStubs(): ThreadSafeListOfStubs {
        return ThreadSafeListOfStubs(mutableListOf(), specToBaseUrlMap)
    }

    companion object {
        internal fun getPartialBySpecificityAndGenerality(partials: List<HttpStubData>): HttpStubData? {
            if (partials.isEmpty()) return null
            
            // Group by specificity (highest first)
            val groupedBySpecificity = partials.groupBy { stubData ->
                stubData.resolveOriginalRequest()?.specificity ?: 0
            }.toSortedMap(reverseOrder())
            
            // Get the group with highest specificity
            val highestSpecificityGroup = groupedBySpecificity.entries.first().value
            
            // If only one partial in the highest specificity group, use it
            if (highestSpecificityGroup.size == 1) {
                return highestSpecificityGroup.single()
            }
            
            // Multiple partials in highest specificity group - group by generality (lowest first)
            val groupedByGenerality = highestSpecificityGroup.groupBy { stubData ->
                stubData.resolveOriginalRequest()?.generality ?: 0
            }.toSortedMap()
            
            // Get the group with lowest generality and pick the first one
            return groupedByGenerality.entries.first().value.first()
        }
    }
}














