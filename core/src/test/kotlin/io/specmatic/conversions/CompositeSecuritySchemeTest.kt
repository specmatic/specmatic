package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CompositeSecuritySchemeTest {

    private val securityScheme = CompositeSecurityScheme(listOf(
        BearerSecurityScheme(configuredToken = "API-SECRET"),
        APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = null)
    ))

    private val securitySchemeWithToken = CompositeSecurityScheme(listOf(
        BearerSecurityScheme(configuredToken = "API-SECRET"),
        APIKeyInQueryParamSecurityScheme(name = "apiKey", apiKey = "1234")
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
        >> HEADER.$AUTHORIZATION
        Expected header named "$AUTHORIZATION" was missing
        >> QUERY.apiKey
        Expected api-key named "apiKey" was missing
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