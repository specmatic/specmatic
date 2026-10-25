package io.specmatic.conversions

sealed interface BearerAuthentication {
    data object HttpBearer : BearerAuthentication

    data class OAuth2(
        val schemeName: String,
        val reference: String? = null,
        val description: String? = null,
        val flows: OpenApiOAuthFlows? = null,
        val extensions: Map<String, Any?>? = null,
        val requiredScopes: List<String> = emptyList(),
    ) : BearerAuthentication

    data class OpenIdConnect(
        val schemeName: String,
        val reference: String? = null,
        val description: String? = null,
        val openIdConnectUrl: String? = null,
        val extensions: Map<String, Any?>? = null,
        val requiredScopes: List<String> = emptyList(),
    ) : BearerAuthentication
}

data class OpenApiOAuthFlows(
    val implicit: OpenApiOAuthFlow? = null,
    val password: OpenApiOAuthFlow? = null,
    val extensions: Map<String, Any?>? = null,
    val clientCredentials: OpenApiOAuthFlow? = null,
    val authorizationCode: OpenApiOAuthFlow? = null,
)

data class OpenApiOAuthFlow(
    val tokenUrl: String? = null,
    val refreshUrl: String? = null,
    val authorizationUrl: String? = null,
    val scopes: Map<String, String>? = null,
    val extensions: Map<String, Any?>? = null,
)
