package io.specmatic.proxy

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpPathPattern
import io.specmatic.core.HttpRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ProxyOperationTest {
    @ParameterizedTest(name = "expected={0}, actual={1}, result={2}")
    @MethodSource("contentTypeExpectationCases")
    fun `should evaluate request content type expectations`(expectedContentType: String?, actualContentType: String?, expectedMatchResult: Boolean) {
        val expectation = when (expectedContentType) {
            null -> ContentTypeExpectation.Unspecified
            "__MUST_BE_ABSENT__" -> ContentTypeExpectation.MustBeAbsent
            else -> ContentTypeExpectation.MustMatch(expectedContentType)
        }

        val operation = ProxyOperation(
            pathPattern = HttpPathPattern.from("/orders"),
            method = "POST",
            requestContentType = expectation
        )

        val headers = if (actualContentType != null) mapOf(CONTENT_TYPE to actualContentType) else emptyMap()
        val request = HttpRequest(
            method = "POST",
            path = "/orders",
            headers = headers
        )

        assertThat(operation.matches(request)).isEqualTo(expectedMatchResult)
    }

    companion object {
        @JvmStatic
        fun contentTypeExpectationCases() = listOf(
            Arguments.of("application/json", "application/json", true),
            Arguments.of("application/*", "application/json", true),
            Arguments.of("application/json", "application/json; charset=utf-8", true),
            Arguments.of("application/json", "text/plain", false),
            Arguments.of("application/json", null, false),
            Arguments.of(null, null, true),
            Arguments.of(null, "application/json", true),
            Arguments.of("__MUST_BE_ABSENT__", null, true),
            Arguments.of("__MUST_BE_ABSENT__", "application/json", false)
        )
    }
}
