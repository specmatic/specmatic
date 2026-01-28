package io.specmatic.conversions

import io.specmatic.core.APIKeySecuritySchemeConfiguration
import io.specmatic.core.BearerSecuritySchemeConfiguration
import io.specmatic.core.OAuth2SecuritySchemeConfiguration
import io.specmatic.core.SpecmaticConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecurityTokenTest {

    @Test
    fun `should extract security token for bearer security scheme from configuration`() {
        val token = "BEARER1234"
        val securityToken = getSecurityTokenForBearerScheme(
            SpecmaticConfig(),
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
            val securityToken = getSecurityTokenForBearerScheme(SpecmaticConfig(), null, schemeName)
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
                SpecmaticConfig(),
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
            SpecmaticConfig(),
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
            val securityToken = getSecurityTokenForBearerScheme(SpecmaticConfig(), null, schemeName)
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
                SpecmaticConfig(),
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
            val securityToken = getSecurityTokenForBearerScheme(SpecmaticConfig(), null, schemeName)
            assertThat(securityToken).isEqualTo(envToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }

    @Test
    fun `should prefer config then scheme env var then legacy env var for bearer scheme`() {
        val configToken = "CONFIG1234"
        val schemeEnvToken = "SCHEME_ENV_1234"
        val legacyEnvToken = "LEGACY_ENV_1234"
        val schemeName = "oAuth2AuthCode"
        val tokenMap = mapOf(
            schemeName to schemeEnvToken,
            SPECMATIC_OAUTH2_TOKEN to legacyEnvToken
        )
        tokenMap.forEach { System.setProperty(it.key, it.value) }

        try {
            val configFirst = getSecurityTokenForBearerScheme(
                SpecmaticConfig(),
                OAuth2SecuritySchemeConfiguration("oauth2", configToken),
                schemeName
            )
            assertThat(configFirst).isEqualTo(configToken)

            val schemeEnvSecond = getSecurityTokenForBearerScheme(
                SpecmaticConfig(),
                null,
                schemeName
            )
            assertThat(schemeEnvSecond).isEqualTo(schemeEnvToken)

            tokenMap.forEach { System.clearProperty(it.key) }
            System.setProperty(SPECMATIC_OAUTH2_TOKEN, legacyEnvToken)

            val legacyFallback = getSecurityTokenForBearerScheme(
                SpecmaticConfig(),
                null,
                schemeName
            )
            assertThat(legacyFallback).isEqualTo(legacyEnvToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
            System.clearProperty(SPECMATIC_OAUTH2_TOKEN)
        }
    }

    @Test
    fun `should extract security token for apikey security scheme from configuration`() {
        val token = "APIKEY1234"
        val securityToken = getSecurityTokenForApiKeyScheme(
            SpecmaticConfig(),
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
            val securityToken = getSecurityTokenForApiKeyScheme(SpecmaticConfig(), null, schemeName)
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
                SpecmaticConfig(),
                APIKeySecuritySchemeConfiguration("apikey", configToken),
                schemeName
            )
            assertThat(securityToken).isEqualTo(configToken)
        } finally {
            tokenMap.forEach { System.clearProperty(it.key) }
        }
    }
}
