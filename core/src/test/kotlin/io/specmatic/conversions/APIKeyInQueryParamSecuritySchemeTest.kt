package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.toViolationReportString
import io.specmatic.core.DefaultMismatchMessages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class APIKeyInQueryParamSecuritySchemeTest {
    @Nested
    inner class FixValueTests {
        @Test
        fun `should preserve a present query api key`() {
            val request = HttpRequest(queryParametersMap = mapOf("apiKey" to "original"))
            val scheme = APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "generated")
            assertThat(scheme.fixValue(request, Resolver())).isEqualTo(request)
        }

        @Test
        fun `should add a missing query api key`() {
            val scheme = APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "generated")
            assertThat(scheme.fixValue(HttpRequest(), Resolver())).isEqualTo(
                HttpRequest().addSecurityQueryParam("apiKey", "generated")
            )
        }
    }

    @Test
    fun `should result in failure when query api key is present`() {
        val httpRequest = HttpRequest(queryParametersMap = mapOf("API-KEY" to "123"))
        val result = APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "123").failIfInRequest(httpRequest, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace(toViolationReportString(
            breadCrumb = "QUERY.API-KEY",
            details = DefaultMismatchMessages.unexpectedKey("query", "API-KEY")
        ))
    }

    @Test
    fun `should not result in failure when query api key is absent`() {
        val httpRequest = HttpRequest(queryParametersMap = emptyMap())
        val result = APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "123").failIfInRequest(httpRequest, Resolver())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should result in failure when query api key is missing`() {
        val httpRequest = HttpRequest(headers = emptyMap())
        val resolver = Resolver(mockMode = false)
        val result = APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "123").matches(httpRequest, resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace(toViolationReportString(
            breadCrumb = "QUERY.API-KEY",
            details = DefaultMismatchMessages.expectedKeyWasMissing(apiKeyParamName, "API-KEY"),
            StandardRuleViolation.REQUIRED_PROPERTY_MISSING
        ))
    }

    @Test
    fun `should not result in failure when query api key is missing and resolver is in mock mode`() {
        val httpRequest = HttpRequest(headers = emptyMap())
        val resolver = Resolver(mockMode = true)
        val result = APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "123").matches(httpRequest, resolver)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
