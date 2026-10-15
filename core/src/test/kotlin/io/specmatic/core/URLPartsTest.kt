package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class URLPartsTest {
    @ParameterizedTest
    @MethodSource("urlWithSpacesProvider")
    fun `URL with spaces in path segment should be percent encoded`(urlWithSpaces: String, encodedUrl: String) {
        val urlParts = URLParts(urlWithSpaces)
        assertThat(urlParts.withEncodedPathSegments()).isEqualTo(encodedUrl)
    }

    @ParameterizedTest
    @MethodSource("encodedUrlProvider")
    fun `URL with percent encoded spaces in path segments should be decoded`(encodedUrl: String, decodedUrl: String) {
        val urlParts = URLParts(encodedUrl)
        assertThat(urlParts.withDecodedPathSegments()).isEqualTo(decodedUrl)
    }

    companion object {
        @JvmStatic
        fun urlWithSpacesProvider(): Stream<Arguments> =
            Stream.of(
                Arguments.arguments("http://example.com/path with spaces?query=param", "http://example.com/path%20with%20spaces?query=param"),
                Arguments.arguments("http://example.com/path with spaces", "http://example.com/path%20with%20spaces")
            )

        @JvmStatic
        fun encodedUrlProvider(): Stream<Arguments> =
            Stream.of(
                Arguments.arguments("http://example.com/path%20with%20spaces?query=param", "http://example.com/path with spaces?query=param"),
                Arguments.arguments("http://example.com/path%20with%20spaces", "http://example.com/path with spaces")
            )
    }
}
