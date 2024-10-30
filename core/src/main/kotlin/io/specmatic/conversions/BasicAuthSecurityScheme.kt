package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.randomString
import org.apache.http.HttpHeaders.AUTHORIZATION
import java.util.Base64

data class BasicAuthSecurityScheme(private val token: String? = null) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest): Result {
        val authHeaderValue: String = httpRequest.headers[AUTHORIZATION]
            ?: return Result.Failure("$AUTHORIZATION header is missing in request")

        if (!authHeaderValue.lowercase().startsWith("basic"))
            return Result.Failure("$AUTHORIZATION header must be prefixed with \"Basic\"")

        val base64Credentials = authHeaderValue.substringAfter(" ").trim()

        return validateBase64EncodedCredentials(base64Credentials)
    }

    private fun validateBase64EncodedCredentials(base64Credentials: String): Result {
        try {
            val decodedBytes = Base64.getDecoder().decode(base64Credentials)
            val credentials = String(decodedBytes)

            if (!credentials.contains(":"))
                return Result.Failure("Base64-encoded credentials in $AUTHORIZATION header is not in the form username:password")
        } catch (e: IllegalArgumentException) {
            return Result.Failure("Invalid base64 encoding in $AUTHORIZATION header")
        }

        return Result.Success()
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(headers = httpRequest.headers.minus(AUTHORIZATION))
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        return httpRequest.copy(
            headers = httpRequest.headers.plus(
                AUTHORIZATION to getAuthorizationHeaderValue(resolver)
            )
        )
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return addToHeaderType(AUTHORIZATION, row, requestPattern)
    }

    override fun isInRow(row: Row): Boolean = row.containsField(AUTHORIZATION)
    private fun getAuthorizationHeaderValue(resolver: Resolver): String {
        val validToken = when {
            token != null -> {
                validatedToken(token)
            }
            dictionaryHasValidToken(resolver) -> {
                resolver.getDictionaryToken(AUTHORIZATION).toStringLiteral()
            }
            else -> {
                randomBasicAuthCredentials()
            }
        }

        return "Basic $validToken"
    }

    private fun dictionaryHasValidToken(resolver: Resolver) =
        resolver.hasDictionaryToken(AUTHORIZATION) && resolver.getDictionaryToken(AUTHORIZATION).toStringLiteral().let {
            it.lowercase()
                .startsWith("basic ") && validateBase64EncodedCredentials(it.substringAfter(" ")) is Result.Success
        }

    private fun validatedToken(token: String): String {
        val result = validateBase64EncodedCredentials(token)

        if(result is Result.Failure)
            throw ContractException(result.reportString())

        return token
    }

    private fun randomBasicAuthCredentials(): String {
        val randomUsername = randomString()
        val randomPassword = randomString()

        return Base64.getEncoder().encodeToString("$randomUsername:$randomPassword".toByteArray())
    }
}
