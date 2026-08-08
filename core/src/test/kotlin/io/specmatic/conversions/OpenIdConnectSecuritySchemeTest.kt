package io.specmatic.conversions

import com.google.common.net.HttpHeaders
import com.fasterxml.jackson.module.kotlin.readValue
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.OpenAPISecurityConfiguration
import io.specmatic.core.OpenIdConnectSecuritySchemeConfiguration
import io.specmatic.core.Result
import io.specmatic.core.SecurityConfiguration
import io.specmatic.core.config.v3.components.SecuritySchemeConfigurationV3
import io.specmatic.core.config.v3.components.SecuritySchemeType
import io.specmatic.core.utilities.yamlMapper
import io.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenIdConnectSecuritySchemeTest {
    @Test
    fun `generates authorization header using configured OIDC token`() {
        val feature = OpenApiSpecification.fromYAML(
            oidcProtectedApi,
            "",
            securityConfiguration = SecurityConfiguration(
                OpenAPI = OpenAPISecurityConfiguration(
                    securitySchemes = mapOf(
                        "oidc" to OpenIdConnectSecuritySchemeConfiguration(token = "configured-token")
                    )
                )
            )
        ).toFeature()

        val result = feature.generateContractTests().single().runTest(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(request.headers).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer configured-token")
                return HttpResponse(status = 200)
            }
        }).result

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `generates authorization header using token named after OIDC scheme`() {
        try {
            System.setProperty("oidc", "environment-token")
            val feature = OpenApiSpecification.fromYAML(oidcProtectedApi, "").toFeature()

            val result = feature.generateContractTests().single().runTest(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer environment-token")
                    return HttpResponse(status = 200)
                }
            }).result

            assertThat(result).isInstanceOf(Result.Success::class.java)
        } finally {
            System.clearProperty("oidc")
        }
    }

    @Test
    fun `deserializes OIDC security configuration in legacy config`() {
        val configuration = yamlMapper.readValue<SecurityConfiguration>(
            """
            OpenAPI:
              securitySchemes:
                oidc:
                  type: openIdConnect
                  token: jwt-token
            """.trimIndent()
        )

        assertThat(configuration.getOpenAPISecurityScheme("oidc"))
            .isEqualTo(OpenIdConnectSecuritySchemeConfiguration(token = "jwt-token"))
    }

    @Test
    fun `maps OIDC v3 security configuration to bearer-token configuration`() {
        val configuration = SecuritySchemeConfigurationV3(
            type = SecuritySchemeType.OPEN_ID_CONNECT,
            token = "jwt-token"
        ).toSecuritySchemeConfiguration()

        assertThat(configuration).isEqualTo(OpenIdConnectSecuritySchemeConfiguration(token = "jwt-token"))
    }

    private val oidcProtectedApi = """
        openapi: 3.0.3
        info:
          title: OIDC protected API
          version: 1.0.0
        paths:
          /products:
            get:
              security:
                - oidc: []
              responses:
                '200':
                  description: Products
        components:
          securitySchemes:
            oidc:
              type: openIdConnect
              openIdConnectUrl: https://issuer.example/.well-known/openid-configuration
    """.trimIndent()
}
