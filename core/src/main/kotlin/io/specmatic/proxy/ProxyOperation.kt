package io.specmatic.proxy

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import javax.activation.MimeType

sealed interface ContentTypeExpectation {
    data object Unspecified : ContentTypeExpectation
    data object MustBeAbsent : ContentTypeExpectation
    data class MustMatch(val value: String) : ContentTypeExpectation
}

data class ProxyOperation(val pathPattern: HttpPathPattern, val method: String, val requestContentType: ContentTypeExpectation = ContentTypeExpectation.Unspecified, val resolver: Resolver = Resolver()) {
    fun matches(request: HttpRequest): Boolean {
        val requestPath = request.path.orEmpty()
        val requestMethod = request.method.orEmpty()
        val requestContentType = request.getHeader(CONTENT_TYPE)
        return matches(requestPath, requestMethod, requestContentType)
    }

    fun matches(actualPath: String, actualMethod: String, actualContentType: String?): Boolean {
        val pathMatches = pathPattern.matches(actualPath, resolver).isSuccess()
        val methodMatches = this.method.equals(actualMethod, ignoreCase = true)
        val contentTypeMatches = matchContentTypeIfPresent (requestContentType, actualContentType)
        return pathMatches && methodMatches && contentTypeMatches
    }

    private fun matchContentTypeIfPresent(expected: ContentTypeExpectation, actualContentType: String?): Boolean {
        return when (expected) {
            ContentTypeExpectation.Unspecified -> true
            ContentTypeExpectation.MustBeAbsent -> actualContentType == null
            is ContentTypeExpectation.MustMatch -> {
                actualContentType != null && MimeType(expected.value).match(MimeType(actualContentType))
            }
        }
    }
}
