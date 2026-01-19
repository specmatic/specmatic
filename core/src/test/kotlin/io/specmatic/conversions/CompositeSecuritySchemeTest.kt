package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.StandardRuleViolation
import io.specmatic.toViolationReportString
import io.specmatic.core.DefaultMismatchMessages
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompositeSecuritySchemeTest {

    private val securityScheme = CompositeSecurityScheme(listOf(
        BearerSecurityScheme(configuredToken = "API-SECRET", schemeName = "BearerScheme"),
        APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = null, schemeName = "ApikeyQueryScheme")
    ))

    private val securitySchemeWithToken = CompositeSecurityScheme(listOf(
        BearerSecurityScheme(configuredToken = "API-SECRET", schemeName = "BearerScheme"),
        APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "1234", schemeName = "ApikeyQueryScheme")
    ))

    @Test
    fun `should return success when request matches all security schemes`() {
        val httpRequest = HttpRequest(
            method = "GET", path ="/",
            headers = mapOf(AUTHORIZATION to "Bearer API-SECRET"), queryParametersMap = mapOf("apiKey" to "1234")
        )
        val result = securityScheme.matches(httpRequest, Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should return failure when request does not match at-least one security scheme`() {
        val httpRequest = HttpRequest(method = "GET", path ="/")
        val result = securityScheme.matches(httpRequest, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).isEqualToNormalizingWhitespace("""
        ${
            toViolationReportString(
                breadCrumb = "HEADER.$AUTHORIZATION",
                details = DefaultMismatchMessages.expectedKeyWasMissing("header", AUTHORIZATION),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "QUERY.apiKey",
                details = DefaultMismatchMessages.expectedKeyWasMissing(apiKeyParamName, "apiKey"),
                StandardRuleViolation.REQUIRED_PROPERTY_MISSING
            )
        }
        """.trimIndent())
    }

    @Test
    fun `should add all security schemes into the request`() {
        val httpRequest = HttpRequest(method = "GET", path ="/")
        val newRequest = securitySchemeWithToken.addTo(httpRequest)

        assertThat(newRequest.headers[AUTHORIZATION]).isEqualTo("Bearer API-SECRET")
        assertThat(newRequest.queryParams.asMap()["apiKey"]).isEqualTo("1234")
    }

    @Test
    fun `should be able to remove all security schemes from the request`() {
        val httpRequest = HttpRequest(
            method = "GET", path ="/",
            headers = mapOf(AUTHORIZATION to "Bearer MY-TOKEN"),
            queryParametersMap = mapOf("apiKey" to "ABC")
        )
        val newRequest = securityScheme.removeParam(httpRequest)

        assertThat(newRequest.headers["API-SECRET"]).isNull()
        assertThat(newRequest.queryParams.asMap()["apiKey"]).isNull()
    }
}