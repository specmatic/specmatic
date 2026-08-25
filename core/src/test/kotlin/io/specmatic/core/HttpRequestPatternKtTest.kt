package io.specmatic.core

import io.specmatic.conversions.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

internal class HttpRequestPatternKtTest {
    @Test
    fun `when generating new content part types with two value options there should be two types generated`() {
        val multiPartTypes = listOf(MultiPartContentPattern(
            "data",
            AnyPattern(listOf(StringPattern(), NumberPattern()), extensions = emptyMap()),
        ))

        val newTypes = newMultiPartBasedOn(multiPartTypes, Row(), Resolver()).toList()

        assertThat(newTypes).hasSize(2)

        assertThat(newTypes).contains(listOf(MultiPartContentPattern("data", NumberPattern())))
        assertThat(newTypes).contains(listOf(MultiPartContentPattern("data", StringPattern())))
    }

    @Test
    fun `when a part is optional there should be two lists generated in which one has the part and the other does not`() {
        val multiPartTypes = listOf(MultiPartContentPattern("data?", StringPattern()))

        val newTypes = newMultiPartBasedOn(multiPartTypes, Row(), Resolver()).toList()

        assertThat(newTypes).hasSize(2)

        assertThat(newTypes).contains(listOf(MultiPartContentPattern("data", StringPattern())))
        assertThat(newTypes).contains(emptyList())
    }

    @ParameterizedTest
    @MethodSource("io.specmatic.core.ScenarioTest#securitySchemaProvider")
    fun `should remove security schemes before header matching occurs even if value is invalid`(securitySchema: OpenAPISecurityScheme) {
        val httpRequestPattern = HttpRequestPattern(
            httpPathPattern = buildHttpPathPattern("/"), method = "POST", securitySchemes = listOf(securitySchema)
        )
        val httpRequest = invalidateSecuritySchemes(HttpRequest("POST", "/"), securitySchema)
        val result = httpRequestPattern.matches(httpRequest, Resolver().disableOverrideUnexpectedKeyCheck())
        val report = result.reportString()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        when(securitySchema) {
            is APIKeyInHeaderSecurityScheme -> assertThat(report).containsOnlyOnce(">> REQUEST.PARAMETERS.HEADER.API-KEY")
            is APIKeyInQueryParamSecurityScheme -> assertThat(report).containsOnlyOnce(">> REQUEST.PARAMETERS.QUERY.API-KEY")
            is BasicAuthSecurityScheme, is BearerSecurityScheme -> assertThat(report).containsOnlyOnce(">> REQUEST.PARAMETERS.HEADER.Authorization")
            is CompositeSecurityScheme -> assertThat(report).satisfies(
                { assertThat(it).containsOnlyOnce(">> REQUEST.PARAMETERS.HEADER.Authorization") },
                { assertThat(it).containsOnlyOnce(">> REQUEST.PARAMETERS.QUERY.API-KEY") },
            )
            else -> throw RuntimeException("Unknown security scheme ${securitySchema::javaClass.name}")
        }
    }

    @Nested
    inner class SecuritySchemeFixRequestTests {
        @Test
        fun `should preserve security headers while fixing other headers`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"),
                method = "POST",
                securitySchemes = listOf(BearerSecurityScheme()),
                headersPattern = HttpHeadersPattern(pattern = mapOf("X-Request-ID" to ExactValuePattern(StringValue("expected")))),
            )

            val fixedRequest = requestPattern.fixRequest(
                resolver = Resolver(),
                request = HttpRequest(
                    path = "/",
                    method = "POST",
                    headers = mapOf("X-Request-ID" to "invalid", AUTHORIZATION to "Bearer original-token")
                ),
            )

            assertThat(fixedRequest).isEqualTo(
                HttpRequest(
                    path = "/",
                    method = "POST",
                    headers = mapOf("X-Request-ID" to "expected")
                ).addSecurityHeader(AUTHORIZATION, "Bearer original-token")
            )
        }

        @Test
        fun `should preserve security query parameters while fixing other query parameters`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"),
                method = "GET",
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("page" to QueryParameterScalarPattern(ExactValuePattern(StringValue("expected"))))),
                securitySchemes = listOf(APIKeyInQueryParamSecurityScheme("apiKey", null))
            )

            val fixedRequest = requestPattern.fixRequest(
                resolver = Resolver(),
                request = HttpRequest(
                    path = "/",
                    method = "GET",
                    queryParametersMap = mapOf("page" to "invalid", "apiKey" to "original-token")
                ),
            )

            assertThat(fixedRequest).isEqualTo(
                HttpRequest(
                    path = "/",
                    method = "GET",
                    queryParametersMap = mapOf("page" to "expected", "apiKey" to "original-token")
                )
            )
        }

        @Test
        fun `should fix an invalid security scheme`() {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(BearerSecurityScheme("expected-token"))
            )

            val fixedRequest = requestPattern.fixRequest(
                resolver = Resolver(),
                request = HttpRequest(
                    path = "/",
                    method = "GET",
                    headers = mapOf(AUTHORIZATION to "Basic invalid-token")
                ),
            )

            assertThat(fixedRequest).isEqualTo(
                HttpRequest(method = "GET", path = "/")
                    .addSecurityHeader(AUTHORIZATION, "Bearer expected-token")
            )
        }

        @Test
        fun `should prefer a full presence scheme over an absent alternative`() {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(
                    APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key"),
                    BearerSecurityScheme("generated-bearer")
                )
            )

            val fixedRequest = requestPattern.fixRequest(
                resolver = Resolver(mockMode = true),
                request = HttpRequest(
                    path = "/",
                    method = "GET",
                    headers = mapOf(AUTHORIZATION to "Bearer original-token")
                ),
            )

            assertThat(fixedRequest).isEqualTo(
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Bearer original-token")
            )
        }

        @Test
        fun `should prefer a full presence scheme over a partial alternative`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"),
                method = "GET",
                securitySchemes = listOf(
                    BearerSecurityScheme("generated-bearer"),
                    CompositeSecurityScheme(
                        listOf(
                            BearerSecurityScheme("generated-bearer"),
                            APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key")
                        )
                    ),
                )
            )

            val fixedRequest = requestPattern.fixRequest(
                resolver = Resolver(mockMode = true),
                request = HttpRequest(
                    path = "/",
                    method = "GET",
                    headers = mapOf(AUTHORIZATION to "Bearer original-token")
                ),
            )

            assertThat(fixedRequest).isEqualTo(
                HttpRequest(method = "GET", path = "/")
                    .addSecurityHeader(AUTHORIZATION, "Bearer original-token")
            )
        }

        @Test
        fun `should fix every scheme in the selected presence group`() {
            val requestPattern = HttpRequestPattern(
                httpPathPattern = buildHttpPathPattern("/"),
                method = "GET",
                securitySchemes = listOf(
                    APIKeyInQueryParamSecurityScheme("apiKey", "original-api-key"),
                    CompositeSecurityScheme(
                        listOf(
                            BearerSecurityScheme("generated-bearer"),
                            APIKeyInQueryParamSecurityScheme("apiKey", "original-api-key")
                        )
                    ),
                )
            )

            val fixedRequest = requestPattern.fixRequest(
                resolver = Resolver(),
                request = HttpRequest(
                    path = "/",
                    method = "GET",
                    headers = mapOf(AUTHORIZATION to "Basic invalid-token"),
                    queryParametersMap = mapOf("apiKey" to "original-api-key")
                ),
            )

            assertThat(fixedRequest).isEqualTo(
                HttpRequest(
                    path = "/",
                    method = "GET",
                    queryParametersMap = mapOf("apiKey" to "original-api-key")
                ).addSecurityHeader(AUTHORIZATION, "Bearer generated-bearer")
            )
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestPatternKtTest#securitySchemesWithExpectedValues")
        fun `should add absent security schemes outside mock mode`(securityScheme: OpenAPISecurityScheme, expectedRequest: HttpRequest) {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(securityScheme)
            )

            assertThat(requestPattern.fixRequest(HttpRequest(method = "GET", path = "/"), Resolver())).isEqualTo(expectedRequest)
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestPatternKtTest#invalidSecuritySchemesInMockMode")
        fun `should fix invalid present security schemes in mock mode without adding absent components`(securityScheme: OpenAPISecurityScheme, request: HttpRequest, expectedRequest: HttpRequest) {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(securityScheme)
            )

            assertThat(requestPattern.fixRequest(request, Resolver(mockMode = true))).isEqualTo(expectedRequest)
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestPatternKtTest#partialCompositeFixCases")
        fun `should complete a partial composite only outside mock mode`(mockMode: Boolean, expectedRequest: HttpRequest) {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(
                    APIKeyInQueryParamSecurityScheme("apiKey", "alternative-api-key"),
                    CompositeSecurityScheme(listOf(
                        BearerSecurityScheme("generated-bearer"),
                        APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key")
                    )),
                )
            )

            val request = HttpRequest(
                path = "/",
                method = "GET",
                headers = mapOf(AUTHORIZATION to "Bearer original-token")
            )

            assertThat(requestPattern.fixRequest(request, Resolver(mockMode = mockMode))).isEqualTo(expectedRequest)
        }

        @Test
        fun `should fix ordinary fields without adding security for a no-security request`() {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(NoSecurityScheme()),
                headersPattern = HttpHeadersPattern(mapOf("X-Request-ID" to ExactValuePattern(StringValue("expected")))),
                httpQueryParamPattern = HttpQueryParamPattern(mapOf("page" to QueryParameterScalarPattern(ExactValuePattern(StringValue("expected"))))),
            )

            val request = HttpRequest(
                method = "GET",
                path = "/",
                headers = mapOf("X-Request-ID" to "invalid"),
                queryParametersMap = mapOf("page" to "invalid")
            )

            assertThat(requestPattern.fixRequest(request, Resolver())).isEqualTo(
                HttpRequest(
                    method = "GET",
                    path = "/",
                    headers = mapOf("X-Request-ID" to "expected"),
                    queryParametersMap = mapOf("page" to "expected")
                )
            )
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestPatternKtTest#securitySchemesWithCaseInsensitiveHeaders")
        fun `should preserve security headers regardless of header name case`(securityScheme: OpenAPISecurityScheme, request: HttpRequest, expectedRequest: HttpRequest) {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(securityScheme)
            )

            assertThat(requestPattern.fixRequest(request, Resolver())).isEqualTo(expectedRequest)
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestPatternKtTest#securitySchemesWithoutPresence")
        fun `should not add absent security schemes in mock mode`(securityScheme: OpenAPISecurityScheme) {
            val requestPattern = HttpRequestPattern(
                method = "GET",
                httpPathPattern = buildHttpPathPattern("/"),
                securitySchemes = listOf(securityScheme)
            )

            val request = HttpRequest(method = "GET", path = "/")
            assertThat(requestPattern.fixRequest(request, Resolver(mockMode = true))).isEqualTo(request)
        }
    }

    companion object {
        @JvmStatic
        fun securitySchemesWithExpectedValues(): Stream<Arguments> = Stream.of(
            Arguments.of(
                NoSecurityScheme(),
                HttpRequest(method = "GET", path = "/")
            ),
            Arguments.of(
                APIKeyInHeaderSecurityScheme("API-KEY", "generated-api-key"),
                HttpRequest(method = "GET", path = "/").addSecurityHeader("API-KEY", "generated-api-key")
            ),
            Arguments.of(
                APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key"),
                HttpRequest(method = "GET", path = "/", queryParametersMap = mapOf("apiKey" to "generated-api-key"))
            ),
            Arguments.of(
                BasicAuthSecurityScheme("dXNlcjpwYXNz"),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Basic dXNlcjpwYXNz")
            ),
            Arguments.of(
                BearerSecurityScheme("generated-bearer"),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Bearer generated-bearer")
            ),
            Arguments.of(
                CompositeSecurityScheme(listOf(
                    BearerSecurityScheme("generated-bearer"),
                    APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key")
                )),
                HttpRequest(method = "GET", path = "/", queryParametersMap = mapOf("apiKey" to "generated-api-key"))
                    .addSecurityHeader(AUTHORIZATION, "Bearer generated-bearer")
            )
        )

        @JvmStatic
        fun invalidSecuritySchemesInMockMode(): Stream<Arguments> = Stream.of(
            Arguments.of(
                BearerSecurityScheme("expected-bearer"),
                HttpRequest(headers = mapOf(AUTHORIZATION to "Basic invalid")),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Bearer expected-bearer")
            ),
            Arguments.of(
                BasicAuthSecurityScheme("dXNlcjpwYXNz"),
                HttpRequest(headers = mapOf(AUTHORIZATION to "Bearer invalid")),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Basic dXNlcjpwYXNz")
            ),
            Arguments.of(
                CompositeSecurityScheme(listOf(
                    BearerSecurityScheme("expected-bearer"),
                    APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key")
                )),
                HttpRequest(headers = mapOf(AUTHORIZATION to "Basic invalid")),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Bearer expected-bearer")
            )
        )

        @JvmStatic
        fun partialCompositeFixCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                false,
                HttpRequest(
                    method = "GET",
                    path = "/",
                    queryParametersMap = mapOf("apiKey" to "generated-api-key")
                ).addSecurityHeader(AUTHORIZATION, "Bearer original-token")
            ),
            Arguments.of(
                true,
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Bearer original-token")
            )
        )

        @JvmStatic
        fun securitySchemesWithCaseInsensitiveHeaders(): Stream<Arguments> = Stream.of(
            Arguments.of(
                APIKeyInHeaderSecurityScheme("API-KEY", "ignored"),
                HttpRequest(headers = mapOf("api-key" to "original-api-key")),
                HttpRequest(method = "GET", path = "/").addSecurityHeader("API-KEY", "original-api-key")
            ),
            Arguments.of(
                BasicAuthSecurityScheme("dXNlcjpwYXNz"),
                HttpRequest(headers = mapOf("authorization" to "Basic dXNlcjpwYXNz")),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Basic dXNlcjpwYXNz")
            ),
            Arguments.of(
                BearerSecurityScheme("ignored"),
                HttpRequest(headers = mapOf("authorization" to "Bearer original-token")),
                HttpRequest(method = "GET", path = "/").addSecurityHeader(AUTHORIZATION, "Bearer original-token")
            )
        )

        @JvmStatic
        fun securitySchemesWithoutPresence(): Stream<Arguments> = Stream.of(
            Arguments.of(NoSecurityScheme()),
            Arguments.of(APIKeyInHeaderSecurityScheme("API-KEY", "generated-api-key")),
            Arguments.of(APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key")),
            Arguments.of(BasicAuthSecurityScheme("dXNlcjpwYXNz")),
            Arguments.of(BearerSecurityScheme("generated-bearer")),
            Arguments.of(CompositeSecurityScheme(listOf(
                BearerSecurityScheme("generated-bearer"),
                APIKeyInQueryParamSecurityScheme("apiKey", "generated-api-key")
            )))
        )

        private fun invalidateSecuritySchemes(request: HttpRequest, scheme: OpenAPISecurityScheme): HttpRequest {
            return when (scheme) {
                is CompositeSecurityScheme -> scheme.schemes.fold(request) { req, it -> invalidateSecuritySchemes(req, it) }
                is BasicAuthSecurityScheme, is BearerSecurityScheme -> request.copy(headers = request.headers.plus(AUTHORIZATION to "INVALID"))
                else -> request
            }
        }
    }
}
