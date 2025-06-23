package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.Dictionary
import io.specmatic.core.Result.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Row
import org.apache.http.HttpHeaders.AUTHORIZATION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.*

class BasicAuthSecuritySchemeTest {
    private val basicAuthSecurityScheme = BasicAuthSecurityScheme()

    @Test
    fun `matches should return failure when authorization header is missing`() {
        val httpRequest = HttpRequest(headers = emptyMap())

        val result = basicAuthSecurityScheme.matches(httpRequest, Resolver())

        assertTrue(result is Failure, "Expected failure when authorization header is missing")
    }

    @Test
    fun `matches should return failure when authorization header is not prefixed with Basic`() {
        val httpRequest = HttpRequest(headers = mapOf(AUTHORIZATION to "Bearer token"))

        val result = basicAuthSecurityScheme.matches(httpRequest, Resolver())

        assertTrue(result is Failure, "Expected failure when authorization header is not prefixed with Basic")
    }

    @Test
    fun `matches should return success when authorization header is correctly prefixed`() {
        val credentials = "charlie123:pqrxyz"
        val httpRequest = HttpRequest(headers = mapOf(AUTHORIZATION to "Basic ${String(Base64.getEncoder().encode(credentials.toByteArray()))}"))

        val result = basicAuthSecurityScheme.matches(httpRequest, Resolver())

        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Success::class.java)
    }

    @Test
    fun `match should fail when decoded basic auth token does not have a colon`() {
        val credentialsWithoutPassword = "charlie123"
        val httpRequest = HttpRequest(headers = mapOf(AUTHORIZATION to "Basic ${String(Base64.getEncoder().encode(credentialsWithoutPassword.toByteArray()))}"))

        val result = basicAuthSecurityScheme.matches(httpRequest, Resolver())

        assertThat(result).withFailMessage(result.reportString()).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `match should fail when basic auth token is not a base64 value`() {
        val nonBase64String = "!@#$%"
        val httpRequest = HttpRequest(headers = mapOf(AUTHORIZATION to "Basic $nonBase64String"))

        val result = basicAuthSecurityScheme.matches(httpRequest, Resolver())

        assertThat(result.reportString()).contains("Invalid base64 encoding")
    }

    @Test
    fun `removeParam should remove authorization header from request`() {
        val httpRequest = HttpRequest(headers = mapOf(AUTHORIZATION to "Basic token"))

        val result = basicAuthSecurityScheme.removeParam(httpRequest)

        assertFalse(result.headers.containsKey(AUTHORIZATION), "Expected authorization header to be removed")
    }

    @Test
    fun `addTo should add authorization header to request`() {
        val httpRequest = HttpRequest(headers = emptyMap())

        val result = basicAuthSecurityScheme.addTo(httpRequest)

        assertTrue(result.headers.containsKey(AUTHORIZATION), "Expected authorization header to be added")

        val headerValue = result.headers[AUTHORIZATION]!!
        val basicAuthToken = headerValue.substringAfter(" ")

        val base64DecodedToken = String(Base64.getDecoder().decode(basicAuthToken))

        assertThat(base64DecodedToken).contains(":")
    }

    @Test
    fun `isInRow should return true when authorization header is present in row`() {
        val row = Row(mapOf(AUTHORIZATION to "Basic token"))

        val result = basicAuthSecurityScheme.isInRow(row)

        assertTrue(result, "Expected true when authorization header is present in row")
    }

    @Test
    fun `isInRow should return false when authorization header is not present in row`() {
        val row = Row(emptyMap())

        val result = basicAuthSecurityScheme.isInRow(row)

        assertFalse(result, "Expected false when authorization header is not present in row")
    }

    @Test
    fun `should not result in failure when authorization header is missing and resolver is in mock mode`() {
        val httpRequest = HttpRequest(headers = emptyMap())
        val resolver = Resolver(mockMode = true)
        val result = basicAuthSecurityScheme.matches(httpRequest, resolver)

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should use the value from dictionary if exists`() {
        val httpRequest = HttpRequest(headers = emptyMap())
        val dictionary = "PARAMETERS: { HEADER: { $AUTHORIZATION: Basic dXNlcjpwYXNzd29yZA== } }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary).updateLookupPath(BreadCrumb.PARAMETERS.value)
        val result = basicAuthSecurityScheme.addTo(httpRequest, resolver)

        assertThat(result.headers[AUTHORIZATION]).isEqualTo("Basic dXNlcjpwYXNzd29yZA==")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Basic 123", "dXNlcjpwYXNzd29yZA=="])
    fun `should complain if the dictionary value is invalid or not prefixed with basic`(invalidValue: String) {
        val httpRequest = HttpRequest(headers = emptyMap())
        val dictionary = "PARAMETERS: { HEADER: { $AUTHORIZATION: $invalidValue } }".let(Dictionary::fromYaml)
        val resolver = Resolver(dictionary = dictionary).updateLookupPath(BreadCrumb.PARAMETERS.value)
        val exception = org.junit.jupiter.api.assertThrows<ContractException> {
            basicAuthSecurityScheme.addTo(httpRequest, resolver)
        }

        assertThat(exception.report()).contains(">> HEADER.Authorization").satisfiesAnyOf(
            { assertThat(it).contains("Authorization header must be prefixed with \"Basic\"") },
            { assertThat(it).contains("Base64-encoded credentials in Authorization header is not in the form username:password")}
        )
    }
}