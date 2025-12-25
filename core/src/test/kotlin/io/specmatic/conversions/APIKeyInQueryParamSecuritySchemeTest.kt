package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.toViolationReportString
import io.specmatic.core.DefaultMismatchMessages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class APIKeyInQueryParamSecuritySchemeTest {

    @Test
    fun `should result in failure when query api key is missing`() {
        val httpRequest = HttpRequest(headers = emptyMap())
        val resolver = Resolver(mockMode = false)
        val result = APIKeyInQueryParamSecurityScheme(name = "API-KEY", apiKey = "123").matches(httpRequest, resolver)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace(toViolationReportString(
            breadCrumb = "QUERY.API-KEY",
            details = DefaultMismatchMessages.expectedKeyWasMissing("api-key", "API-KEY"),
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