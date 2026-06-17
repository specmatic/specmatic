package io.specmatic.core

import io.specmatic.core.pattern.Row
import io.specmatic.core.value.Value

internal interface UndeclaredRequestVariant {
    val responseStatus: Int
    fun requestExampleForGeneration(): HttpRequest? = null
    fun toUndeclaredRequest(request: HttpRequest): HttpRequest
    fun stubRequestPatternFor(request: HttpRequest, resolver: Resolver): HttpRequestPattern? = null
    fun scenarioFromExampleRow(
        row: Row,
        resolver: Resolver,
        newExpectedFacts: Map<String, Value>,
        ignoreFailure: Boolean,
        generativePrefix: String
    ): Scenario? = null
    fun requestBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean
    fun exampleRequestBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean
    fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result
    fun disallowedMethodFor405Example(): String? = null
    fun unsupportedContentTypeFor415Example(): String? = null
}

internal fun String?.baseMediaType(): String? =
    this?.substringBefore(";")?.trim()?.takeIf(String::isNotBlank)

internal fun Set<String>.baseMediaTypes(): Set<String> =
    mapNotNull { it.baseMediaType()?.lowercase() }.toSet()
