package io.specmatic.core

import io.ktor.http.HttpStatusCode

internal interface UndeclaredRequestVariant {
    val responseStatus: Int
    fun requestExampleToUseInsteadOfGenerating(requestExample: HttpRequest?): HttpRequest? = null
    fun applyToGeneratedRequest(request: HttpRequest, requestExample: HttpRequest?): HttpRequest
    fun exactRequestPatternFor(request: HttpRequest, resolver: Resolver): UndeclaredRequestPatternResult? = null
    fun stubRequestPatternFor(request: HttpRequest, resolver: Resolver): HttpRequestPattern? =
        exactRequestPatternFor(request, resolver)?.requestPattern
    fun requestBelongsToPattern(request: HttpRequest, resolver: Resolver): Boolean
    fun exampleRequestBelongsToPattern(request: HttpRequest, resolver: Resolver): Boolean
    fun matchesUndeclaredRequest(request: HttpRequest, resolver: Resolver): Result
}

internal data class UndeclaredRequestPatternResult(
    val requestPattern: HttpRequestPattern,
    val requestContentTypeForReport: String? = null
)

internal fun String?.baseMediaType(): String? =
    this?.substringBefore(";")?.trim()?.takeIf(String::isNotBlank)

internal fun Set<String>.baseMediaTypes(): Set<String> =
    mapNotNull { it.baseMediaType()?.lowercase() }.toSet()

internal fun UndeclaredRequestVariantMetadata.toUndeclaredRequestVariant(
    requestPattern: HttpRequestPattern
): UndeclaredRequestVariant? =
    when (responseStatus) {
        HttpStatusCode.MethodNotAllowed.value -> UndeclaredMethod405Variant(requestPattern, this)
        HttpStatusCode.UnsupportedMediaType.value -> UndeclaredMediaType415Variant(requestPattern, this)
        else -> null
    }

internal val PREFERRED_HTTP_METHODS_FOR_405: List<String> =
    listOf("PATCH", "POST", "PUT", "DELETE", "GET", "HEAD", "OPTIONS", "TRACE")

internal fun Collection<String>.firstMethodNotDeclaredForPath(): String? {
    val declaredMethods = map { it.uppercase() }.toSet()
    return PREFERRED_HTTP_METHODS_FOR_405.firstOrNull { it !in declaredMethods }
}
