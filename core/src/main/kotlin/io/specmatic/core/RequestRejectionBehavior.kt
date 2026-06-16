package io.specmatic.core

import io.specmatic.core.pattern.Row
import io.specmatic.core.value.Value

internal interface RequestRejectionBehavior {
    val responseStatus: Int
    fun requestExampleForGeneration(): HttpRequest? = null
    fun generateRejectedRequest(request: HttpRequest): HttpRequest
    fun requestPatternForStub(request: HttpRequest, resolver: Resolver): HttpRequestPattern? = null
    fun scenarioFromRequestExampleRow(
        row: Row,
        resolver: Resolver,
        newExpectedFacts: Map<String, Value>,
        ignoreFailure: Boolean,
        generativePrefix: String
    ): Scenario? = null
    fun canOwnRequest(request: HttpRequest, resolver: Resolver): Boolean
    fun exampleBelongsToScenario(request: HttpRequest, resolver: Resolver): Boolean
    fun matchesRejectedRequest(request: HttpRequest, resolver: Resolver): Result
}

internal fun String?.requestRejectionNormalizedContentType(): String? =
    this?.substringBefore(";")?.trim()?.takeIf(String::isNotBlank)

internal fun Set<String>.requestRejectionNormalizedContentTypes(): Set<String> =
    mapNotNull { it.requestRejectionNormalizedContentType()?.lowercase() }.toSet()
