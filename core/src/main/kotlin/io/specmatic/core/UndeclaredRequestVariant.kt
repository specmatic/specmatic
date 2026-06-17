package io.specmatic.core

import io.specmatic.core.pattern.Row
import io.specmatic.core.value.Value

internal interface UndeclaredRequestVariant {
    val responseStatus: Int
    fun requestExampleForGeneration(): HttpRequest? = null
    fun applyToGeneratedRequest(request: HttpRequest): HttpRequest
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
    fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result
}

internal fun String?.normalizedRequestVariantContentType(): String? =
    this?.substringBefore(";")?.trim()?.takeIf(String::isNotBlank)

internal fun Set<String>.normalizedRequestVariantContentTypes(): Set<String> =
    mapNotNull { it.normalizedRequestVariantContentType()?.lowercase() }.toSet()
