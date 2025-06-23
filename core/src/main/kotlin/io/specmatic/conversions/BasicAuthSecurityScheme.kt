package io.specmatic.conversions

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.StringValue
import org.apache.http.HttpHeaders.AUTHORIZATION
import java.util.Base64

data class BasicAuthSecurityScheme(private val token: String? = null) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest, resolver: Resolver): Result {
        val authHeaderValue: String = httpRequest.headers[AUTHORIZATION] ?: return when(resolver.mockMode) {
            true -> Result.Success()
            else -> Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = resolver.mismatchMessages.expectedKeyWasMissing("Header", AUTHORIZATION)
            )
        }

        if (!authHeaderValue.lowercase().startsWith("basic")) {
            return Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = "$AUTHORIZATION header must be prefixed with \"Basic\""
            )
        }

        val base64Credentials = authHeaderValue.substringAfter(" ").trim()
        return validateBase64EncodedCredentials(base64Credentials)
    }

    private fun validateBase64EncodedCredentials(base64Credentials: String): Result {
        try {
            val decodedBytes = Base64.getDecoder().decode(base64Credentials)
            val credentials = String(decodedBytes)

            if (!credentials.contains(":")) return Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = "Base64-encoded credentials in $AUTHORIZATION header is not in the form username:password"
            )
        } catch (e: IllegalArgumentException) {
            return Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = "Invalid base64 encoding in $AUTHORIZATION header"
            )
        }

        return Result.Success()
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(headers = httpRequest.headers.minus(AUTHORIZATION))
    }

    override fun addTo(httpRequest: HttpRequest, resolver: Resolver): HttpRequest {
        return httpRequest.addSecurityHeader(AUTHORIZATION, getAuthorizationHeaderValue(resolver))
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return addToHeaderType(AUTHORIZATION, row, requestPattern)
    }

    override fun copyFromTo(originalRequest: HttpRequest, newHttpRequest: HttpRequest): HttpRequest {
        if (!originalRequest.headers.containsKey(AUTHORIZATION)) return newHttpRequest
        return newHttpRequest.addSecurityHeader(AUTHORIZATION, originalRequest.headers.getValue(AUTHORIZATION))
    }

    override fun isInRow(row: Row): Boolean = row.containsField(AUTHORIZATION)

    override fun isInRequest(request: HttpRequest, complete: Boolean): Boolean {
        return request.hasHeader(AUTHORIZATION)
    }

    private fun getAuthorizationHeaderValue(resolver: Resolver): String {
        val tokenFromDictionary = getTokenFromDictionary(resolver)

        val validToken = when {
            token != null -> {
                validatedToken(token)
            }
            tokenFromDictionary != null -> {
                return tokenFromDictionary.unwrapOrContractException()
            }
            else -> {
                randomBasicAuthCredentials()
            }
        }

        return "Basic $validToken"
    }

    private fun getTokenFromDictionary(resolver: Resolver): ReturnValue<String>? {
        val updatedResolver = resolver.updateLookupForParam(BreadCrumb.HEADER.value)
        val dictionaryValue = updatedResolver.dictionary.getValueFor(AUTHORIZATION, StringPattern(), updatedResolver)
        val authHeader = dictionaryValue?.unwrapOrContractException() ?: return null

        if (authHeader !is StringValue) return HasFailure(Result.Failure(
            breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
            message = "Header $AUTHORIZATION must be a string."
        ))

        val headerValue = authHeader.nativeValue
        if (!headerValue.lowercase().startsWith("basic ")) {
            return HasFailure(Result.Failure(
                breadCrumb = BreadCrumb.HEADER.with(AUTHORIZATION),
                message = "$AUTHORIZATION header must be prefixed with \"Basic\""
            ))
        }

        val base64Credentials = headerValue.substringAfter(" ").trim()
        return validateBase64EncodedCredentials(base64Credentials).toReturnValue(headerValue)
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
