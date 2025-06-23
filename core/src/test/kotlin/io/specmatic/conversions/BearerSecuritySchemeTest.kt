package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BearerSecuritySchemeTest {
    private val scheme = BearerSecurityScheme()

    @Test
    fun `authentication header starts with Bearer when using Bearer security scheme`() {
        val request = scheme.addTo(
            HttpRequest(
                method = "POST",
                path = "/customer",
            ),
        )
        assertThat(request.headers[AUTHORIZATION]).startsWith("Bearer ")
    }

    @ParameterizedTest
    @ValueSource(strings = [AUTHORIZATION, "authorization", "AUTHORIZATION"])
    fun `Bearer security scheme authorization header matching should be case insensitive`(authorizationHeaderName: String) {
        val requestWithBearer = HttpRequest(method = "POST", path = "/customer", headers = mapOf(authorizationHeaderName to "Bearer foo"))
        assertThat(scheme.matches(requestWithBearer, Resolver()).isSuccess()).isTrue
    }

    @Test
    fun `Bearer security scheme does not matches requests with authorization header not set`() {
        val requestWithoutHeader = HttpRequest(method = "POST", path = "/customer")
        with(scheme.matches(requestWithoutHeader, Resolver())) {
            assertThat(isSuccess()).isFalse
            assertThat(this.reportString())
                .contains(">> HEADER.Authorization")
                .contains("Expected header named \"Authorization\" was missing")
        }
    }

    @Test
    fun `Bearer security scheme does not matches requests with authorization header without bearer prefix`() {
        val requestWithoutBearer = HttpRequest(method = "POST", path = "/customer", headers = mapOf(AUTHORIZATION to "foo"))
        with(scheme.matches(requestWithoutBearer, Resolver())) {
            assertThat(isSuccess()).isFalse
            assertThat(this.reportString()).contains("must be prefixed")
        }
    }

    @Test
    fun `adds authorization header with the token to request when request does not contain authorization header`() {
        val updatedHttpRequest = BearerSecurityScheme("abcd1234").addTo(HttpRequest())
        assertThat(updatedHttpRequest.headers[AUTHORIZATION]).isEqualTo("Bearer abcd1234")
    }

    @ParameterizedTest
    @ValueSource(strings = [AUTHORIZATION, "authorization", "AUTHORIZATION"])
    fun `replaces existing authorization header with any case with a new authorization header along with set token`(authorizationHeaderName: String) {
        val updatedHttpRequest = BearerSecurityScheme("abcd1234").addTo(HttpRequest(
            headers = mapOf(authorizationHeaderName to "Bearer efgh5678")
        ))
        assertThat(updatedHttpRequest.headers[AUTHORIZATION]).isEqualTo("Bearer abcd1234")
        assertThat(updatedHttpRequest.headers.filterKeys {
            it.equals(
                AUTHORIZATION,
                ignoreCase = true
            )
        }.count()).isEqualTo(1)
    }

    @Test
    fun `should not result in failure when authorization header is missing and resolver is in mock mode`() {
        val httpRequest = HttpRequest(headers = emptyMap())
        val resolver = Resolver(mockMode = true)
        val result = BearerSecurityScheme("abcd1234").matches(httpRequest, resolver)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
