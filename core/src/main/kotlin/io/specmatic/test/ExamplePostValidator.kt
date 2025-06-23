package io.specmatic.test

import io.specmatic.core.*
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.value.*
import io.specmatic.test.asserts.Assert
import io.specmatic.test.asserts.parsedAssert

object ExamplePostValidator: ResponseValidator {
    override fun postValidate(scenario: Scenario, originalScenario: Scenario, httpRequest: HttpRequest, httpResponse: HttpResponse): Result? {
        if (scenario.isNegative) return null
        val asserts = scenario.exampleRow?.toAsserts(scenario.resolver)?.takeIf(List<*>::isNotEmpty) ?: return null

        val actualFactStore = httpRequest.toFactStore(originalScenario) + ExampleProcessor.getFactStore()
        val currentFactStore = httpResponse.toFactStore(originalScenario)

        val results = asserts.map { it.assert(currentFactStore, actualFactStore) }

        val finalResults = results.filterIsInstance<Result.Failure>().ifEmpty { return null }
        return Result.fromFailures(finalResults)
    }

    private fun Row.toAsserts(resolver: Resolver): List<Assert> {
        val responseExampleBody = this.responseExampleForAssertion ?: return emptyList()

        val responseHeaderPrefix = BreadCrumb.RESPONSE.plus(BreadCrumb.HEADER).value
        val headerAsserts = responseExampleBody.headers.map {
            parsedAssert(responseHeaderPrefix, it.key, StringValue(it.value), resolver)
        }.filterNotNull()

        val responseBodyPrefix = BreadCrumb.RESPONSE.plus(BreadCrumb.BODY).value
        return responseExampleBody.body.traverse(
            onScalar = { value, key -> mapOf(key to parsedAssert(responseBodyPrefix, key, value, resolver)) },
            onAssert = { value, key -> mapOf(key to parsedAssert(responseBodyPrefix, key, value, resolver)) }
        ).values.filterNotNull() + headerAsserts
    }

    private fun HttpRequest.toFactStore(scenario: Scenario): Map<String, Value> {
        val pathParams = if (scenario.httpRequestPattern.httpPathPattern != null && this.path != null) {
            val paramsMap = scenario.httpRequestPattern.httpPathPattern.extractPathParams(this.path, scenario.resolver)
            val patternMap = scenario.httpRequestPattern.httpPathPattern.pathParameters().associate { it.key.orEmpty() to it.pattern }
            paramsMap.toFactStore(
                prefix = BreadCrumb.REQUEST.plus(BreadCrumb.PARAM_PATH).value,
                patternMap = patternMap,
                resolver = scenario.resolver,
            )
        } else emptyMap()

        val queryParams = queryParams.asValueMap().mapValues { it.value.toStringLiteral() }.toFactStore(
            prefix = BreadCrumb.REQUEST.plus(BreadCrumb.PARAM_QUERY).value,
            patternMap = scenario.httpRequestPattern.httpQueryParamPattern.queryPatterns,
            resolver = scenario.resolver,
        )

        val headers = headers.toFactStore(
            prefix = BreadCrumb.REQUEST.plus(BreadCrumb.PARAM_HEADER).value,
            patternMap = scenario.httpRequestPattern.headersPattern.pattern,
            resolver = scenario.resolver,
        )

        val body = body.traverse(
            prefix = BreadCrumb.REQUEST.plus(BreadCrumb.BODY).value, onScalar = ::toFactEntry, onComposite = ::toFactEntry
        )

        return buildMap {
            this.putAll(pathParams)
            this.putAll(queryParams)
            this.putAll(headers)
            this.putAll(body)
            // Empty entries to satisfy dynamic path creation
            this[BreadCrumb.PARAMETERS.value] = JSONObjectValue()
            this[BreadCrumb.REQUEST.value] = JSONObjectValue()
        }
    }

    private fun HttpResponse.toFactStore(scenario: Scenario): Map<String, Value> {
        val headers = headers.toFactStore(
            prefix = BreadCrumb.RESPONSE.plus(BreadCrumb.PARAM_HEADER).value,
            patternMap = scenario.httpResponsePattern.headersPattern.pattern,
            resolver = scenario.resolver
        )

        val body = body.traverse(
            prefix = BreadCrumb.RESPONSE.plus(BreadCrumb.BODY).value, onScalar = ::toFactEntry, onComposite = ::toFactEntry
        )

        return buildMap {
            this.putAll(headers)
            this.putAll(body)
            // Empty entries to satisfy dynamic path creation
            this[BreadCrumb.RESPONSE.value] = JSONObjectValue()
        }
    }

    private fun Map<String, String>.toFactStore(prefix: String, patternMap: Map<String, Pattern>, resolver: Resolver): Map<String, Value> {
        return mapValues {
            runCatching {
                val pattern = patternMap[it.key] ?: patternMap["${it.key}?"] ?: return@runCatching StringValue(it.value)
                pattern.parse(it.value, resolver)
            }.getOrDefault(StringValue(it.value))
        }.mapKeys { "$prefix.${it.key}" }
    }

    private fun toFactEntry(value: Value, prefix: String): Map<String, Value> = mapOf(prefix to value)
}
