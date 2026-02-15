package io.specmatic.conversions

import io.specmatic.core.SecuritySchemeConfiguration
import io.specmatic.core.SpecmaticConfig

@Deprecated("This will be deprecated shortly.Use the security scheme name as the environment variable.")
const val SPECMATIC_OAUTH2_TOKEN = "SPECMATIC_OAUTH2_TOKEN"

const val SPECMATIC_BASIC_AUTH_TOKEN = "SPECMATIC_BASIC_AUTH_TOKEN"

fun getSecurityTokenForBasicAuthScheme(
    specmaticConfig: SpecmaticConfig,
    securitySchemeConfiguration: SecuritySchemeConfiguration?,
    schemeName: String,
): String? {
    return specmaticConfig.getBasicAuthSecurityToken(schemeName, securitySchemeConfiguration)
}

fun getSecurityTokenForBearerScheme(
    specmaticConfig: SpecmaticConfig,
    securitySchemeConfiguration: SecuritySchemeConfiguration?,
    schemeName: String,
): String? {
    return specmaticConfig.getBearerSecurityToken(schemeName, securitySchemeConfiguration)
}

fun getSecurityTokenForApiKeyScheme(
    specmaticConfig: SpecmaticConfig,
    securitySchemeConfiguration: SecuritySchemeConfiguration?,
    schemeName: String,
): String? {
    return specmaticConfig.getApiKeySecurityToken(schemeName, securitySchemeConfiguration)
}
