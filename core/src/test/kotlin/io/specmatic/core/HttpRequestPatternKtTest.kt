package io.specmatic.core

import io.specmatic.conversions.*
import io.specmatic.core.pattern.*
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

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
        val result = httpRequestPattern.matches(httpRequest, Resolver().disableOverrideUnexpectedKeycheck())
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

    companion object {
        private fun invalidateSecuritySchemes(request: HttpRequest, scheme: OpenAPISecurityScheme): HttpRequest {
            return when (scheme) {
                is CompositeSecurityScheme -> scheme.schemes.fold(request) { req, it -> invalidateSecuritySchemes(req, it) }
                is BasicAuthSecurityScheme, is BearerSecurityScheme -> request.copy(headers = request.headers.plus(AUTHORIZATION to "INVALID"))
                else -> request
            }
        }
    }
}