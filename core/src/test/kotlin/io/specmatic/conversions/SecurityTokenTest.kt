package io.specmatic.conversions

import io.specmatic.core.config.APIKeySecuritySchemeConfiguration
import io.specmatic.core.config.BearerSecuritySchemeConfiguration
import io.specmatic.core.config.OAuth2SecuritySchemeConfiguration
import io.specmatic.core.SpecmaticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecurityTokenTest {

    @Test
    fun `should extract security token for bearer security scheme from configuration`() {
        val token = "BEARER1234"
        val securityToken = getSecurityTokenForBearerScheme(
            SpecmaticConfig.default(),
            BearerSecuritySchemeConfiguration("bearer", token),
            "BearerAuth",
        )
        assertThat(securityToken).isEqualTo(token)
    }

    @Test
    fun `should extract security token for bearer security scheme from environment variable`() {
        val token = "BEARER1234"
        val schemeName = "BearerAuth"
        val tokenMap = mapOf(schemeName to token)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForBearerScheme(SpecmaticConfig.default(), null, schemeName)
            assertThat(securityToken).isEqualTo(token)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should prefer the security token from configuration over the environment variable for bearer security scheme`() {
        val envToken = "ENV1234"
        val configToken = "CONFIG1234"
        val schemeName = "BearerAuth"
        val tokenMap = mapOf(schemeName to envToken)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForBearerScheme(
                SpecmaticConfig.default(),
                BearerSecuritySchemeConfiguration("bearer", configToken),
                schemeName
            )
            assertThat(securityToken).isEqualTo(configToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should extract security token for oauth2 security scheme from configuration`() {
        val token = "OAUTH1234"
        val securityToken = getSecurityTokenForBearerScheme(
            SpecmaticConfig.default(),
            OAuth2SecuritySchemeConfiguration("oauth2", token),
            "oAuth2AuthCode",
        )
        assertThat(securityToken).isEqualTo(token)
    }

    @Test
    fun `should extract security token for oauth2 security scheme from environment variable`() {
        val token = "OAUTH1234"
        val schemeName = "oAuth2AuthCode"
        val tokenMap = mapOf(schemeName to token)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForBearerScheme(SpecmaticConfig.default(), null, schemeName)
            assertThat(securityToken).isEqualTo(token)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should prefer the security token from configuration over the environment variable for oauth2 security scheme`() {
        val envToken = "ENV1234"
        val configToken = "CONFIG1234"
        val schemeName = "oAuth2AuthCode"
        val tokenMap = mapOf(schemeName to envToken)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForBearerScheme(
                SpecmaticConfig.default(),
                OAuth2SecuritySchemeConfiguration("oauth2", configToken),
                schemeName
            )
            assertThat(securityToken).isEqualTo(configToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should pick up the security token from the SPECMATIC_OAUTH2_TOKEN environment variable as a fallback for bearer security scheme`() {
        val envToken = "ENV1234"
        val schemeName = "oAuth2AuthCode"
        val tokenMap = mapOf(SPECMATIC_OAUTH2_TOKEN to envToken)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForBearerScheme(SpecmaticConfig.default(), null, schemeName)
            assertThat(securityToken).isEqualTo(envToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should prefer config then scheme env var then legacy env var for bearer scheme`() {
        val configBearerToken = "CONFIG1234"
        val schemeEnvBearerToken = "SCHEME_ENV_1234"
        val legacyEnvBearerToken = "LEGACY_ENV_1234"
        val schemeName = "oAuth2AuthCode"
        val tokenMap = mapOf(
            schemeName to schemeEnvBearerToken,
            SPECMATIC_OAUTH2_TOKEN to legacyEnvBearerToken
        )
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val tokenFromConfigWhenEnvPresent = getSecurityTokenForBearerScheme(
                SpecmaticConfig.default(),
                OAuth2SecuritySchemeConfiguration("oauth2", configBearerToken),
                schemeName
            )
            assertThat(tokenFromConfigWhenEnvPresent).isEqualTo(configBearerToken)

            val tokenFromSchemeEnvWhenNoConfig = getSecurityTokenForBearerScheme(
                SpecmaticConfig.default(),
                null,
                schemeName
            )
            assertThat(tokenFromSchemeEnvWhenNoConfig).isEqualTo(schemeEnvBearerToken)

            tokenMap.forEach { System.clearProperty(it.key) }
            System.setProperty(SPECMATIC_OAUTH2_TOKEN, legacyEnvBearerToken)

            val tokenFromLegacyEnvWhenSchemeEnvMissing = getSecurityTokenForBearerScheme(
                SpecmaticConfig.default(),
                null,
                schemeName
            )
            assertThat(tokenFromLegacyEnvWhenSchemeEnvMissing).isEqualTo(legacyEnvBearerToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
            System.clearProperty(SPECMATIC_OAUTH2_TOKEN)
        }
    }

    @Test
    fun `should extract security token for apikey security scheme from configuration`() {
        val token = "APIKEY1234"
        val securityToken = getSecurityTokenForApiKeyScheme(
            SpecmaticConfig.default(),
            APIKeySecuritySchemeConfiguration("apiKey", token),
            "ApiKeyAuthHeader"
        )
        assertThat(securityToken).isEqualTo(token)
    }

    @Test
    fun `should extract security token for apikey security scheme from environment variable`() {
        val token = "APIKEY1234"
        val schemeName = "ApiKeyAuthHeader"
        val tokenMap = mapOf(schemeName to token)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForApiKeyScheme(SpecmaticConfig.default(), null, schemeName)
            assertThat(securityToken).isEqualTo(token)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should prefer the security token from configuration over the environment variable for apikey security scheme`() {
        val envToken = "ENV1234"
        val configToken = "CONFIG1234"
        val schemeName = "ApiKeyAuthHeader"
        val tokenMap = mapOf(schemeName to envToken)
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val securityToken = getSecurityTokenForApiKeyScheme(
                SpecmaticConfig.default(),
                APIKeySecuritySchemeConfiguration("apikey", configToken),
                schemeName
            )
            assertThat(securityToken).isEqualTo(configToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }
}
