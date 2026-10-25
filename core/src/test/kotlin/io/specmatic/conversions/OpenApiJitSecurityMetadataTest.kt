package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OpenApiJitSecurityMetadataTest {
    @Nested
    inner class OAuth2MetadataTests {
        @Test
        fun `retains client credentials endpoint and only operation required scopes`() {
            val securitySchemes = feature(
                securityScheme = """
                OrdersOAuth:
                  type: oauth2
                  flows:
                    clientCredentials:
                      tokenUrl: https://auth.example.com/token
                      scopes:
                        orders:read: Read orders
                        orders:write: Write orders
                """.trimIndent(),
                operationSecurity = "- OrdersOAuth: [orders:read]",
            ).scenarios.single().httpRequestPattern.securitySchemes

            assertThat(securitySchemes).containsExactly(
                oauth2Bearer(requiredScopes = listOf("orders:read"))
            )
        }

        @Test
        fun `does not treat authorization code flow as client credentials`() {
            val scheme = feature(
                securityScheme = """
                UserOAuth:
                  type: oauth2
                  flows:
                    authorizationCode:
                      authorizationUrl: https://auth.example.com/authorize
                      tokenUrl: https://auth.example.com/token
                      scopes:
                        profile:read: Read profile
                """.trimIndent(),
                operationSecurity = "- UserOAuth: [profile:read]",
            ).scenarios.single().httpRequestPattern.securitySchemes.single()

            assertThat(scheme).isEqualTo(
                oauth2Bearer(
                    schemeName = "UserOAuth",
                    requiredScopes = listOf("profile:read"),
                    flows = oauth2Flows(
                        clientCredentials = null,
                        authorizationCode = OpenApiOAuthFlow(
                            refreshUrl = null,
                            extensions = null,
                            tokenUrl = "https://auth.example.com/token",
                            scopes = mapOf("profile:read" to "Read profile"),
                            authorizationUrl = "https://auth.example.com/authorize",
                        ),
                    ),
                )
            )
        }

        @Test
        fun `preserves all OAuth2 flow metadata and scheme extensions`() {
            val securityScheme = feature(
                securityScheme = """
                OrdersOAuth:
                  type: oauth2
                  description: OAuth scheme description
                  x-provider: auth.example.com
                  flows:
                    implicit:
                      authorizationUrl: https://auth.example.com/implicit
                      refreshUrl: https://auth.example.com/implicit-refresh
                      scopes: { profile:read: Read profile }
                      x-flow: implicit
                    password:
                      tokenUrl: https://auth.example.com/password
                      refreshUrl: https://auth.example.com/password-refresh
                      scopes: { profile:write: Update profile }
                      x-flow: password
                    clientCredentials:
                      tokenUrl: https://auth.example.com/client
                      refreshUrl: https://auth.example.com/client-refresh
                      scopes: { orders:read: Read orders }
                      x-flow: client-credentials
                    authorizationCode:
                      authorizationUrl: https://auth.example.com/authorize
                      tokenUrl: https://auth.example.com/code
                      refreshUrl: https://auth.example.com/code-refresh
                      scopes: { orders:write: Update orders }
                      x-flow: authorization-code
                    x-flows: provider metadata
                """.trimIndent(),
                operationSecurity = "- OrdersOAuth: [orders:read]",
            ).scenarios.single().httpRequestPattern.securitySchemes.single() as BearerSecurityScheme

            assertThat(securityScheme).isEqualTo(
                oauth2Bearer(
                    requiredScopes = listOf("orders:read"),
                    description = "OAuth scheme description",
                    extensions = mapOf("x-provider" to "auth.example.com"),
                    flows = OpenApiOAuthFlows(
                        implicit = OpenApiOAuthFlow(
                            tokenUrl = null,
                            extensions = mapOf("x-flow" to "implicit"),
                            scopes = mapOf("profile:read" to "Read profile"),
                            authorizationUrl = "https://auth.example.com/implicit",
                            refreshUrl = "https://auth.example.com/implicit-refresh",
                        ),
                        password = OpenApiOAuthFlow(
                            authorizationUrl = null,
                            extensions = mapOf("x-flow" to "password"),
                            tokenUrl = "https://auth.example.com/password",
                            scopes = mapOf("profile:write" to "Update profile"),
                            refreshUrl = "https://auth.example.com/password-refresh",
                        ),
                        clientCredentials = OpenApiOAuthFlow(
                            authorizationUrl = null,
                            tokenUrl = "https://auth.example.com/client",
                            scopes = mapOf("orders:read" to "Read orders"),
                            extensions = mapOf("x-flow" to "client-credentials"),
                            refreshUrl = "https://auth.example.com/client-refresh",
                        ),
                        authorizationCode = OpenApiOAuthFlow(
                            tokenUrl = "https://auth.example.com/code",
                            scopes = mapOf("orders:write" to "Update orders"),
                            refreshUrl = "https://auth.example.com/code-refresh",
                            extensions = mapOf("x-flow" to "authorization-code"),
                            authorizationUrl = "https://auth.example.com/authorize",
                        ),
                        extensions = mapOf("x-flows" to "provider metadata"),
                    ),
                )
            )
        }

        @Test
        fun `inherits global scopes and honors operation overrides and explicit unsecured operations`() {
            val feature = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.3
                info:
                  title: OAuth scope inheritance
                  version: 1.0.0
                paths:
                  /inherited:
                    get:
                      responses:
                        '200': { description: OK }
                  /overridden:
                    get:
                      security:
                        - OrdersOAuth: [orders:write]
                      responses:
                        '200': { description: OK }
                  /unsecured:
                    get:
                      security: []
                      responses:
                        '200': { description: OK }
                components:
                  securitySchemes:
                    OrdersOAuth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: https://auth.example.com/token
                          scopes:
                            orders:read: Read orders
                            orders:write: Write orders
                security:
                  - OrdersOAuth: [orders:read]
                """.trimIndent(),
                "./openapi.yaml",
            ).toFeature()

            assertThat(feature.scenarios.single { it.path == "/inherited" }.httpRequestPattern.securitySchemes)
                .containsExactly(
                    oauth2Bearer(requiredScopes = listOf("orders:read"))
                )

            assertThat(feature.scenarios.single { it.path == "/overridden" }.httpRequestPattern.securitySchemes)
                .containsExactly(
                    oauth2Bearer(requiredScopes = listOf("orders:write"))
                )

            assertThat(feature.scenarios.single { it.path == "/unsecured" }.httpRequestPattern.securitySchemes)
                .containsExactly(NoSecurityScheme())
        }

        @Test
        fun `preserves OR alternatives AND composites and anonymous alternatives`() {
            val feature = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.3
                info:
                  title: OAuth requirements
                  version: 1.0.0
                paths:
                  /or:
                    get:
                      security:
                        - OrdersOAuth: [orders:read]
                        - ApiKey: []
                      responses:
                        '200': { description: OK }
                  /and:
                    get:
                      security:
                        - OrdersOAuth: [orders:write]
                          ApiKey: []
                      responses:
                        '200': { description: OK }
                  /anonymous:
                    get:
                      security:
                        - {}
                        - OrdersOAuth: [orders:read]
                      responses:
                        '200': { description: OK }
                components:
                  securitySchemes:
                    OrdersOAuth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: https://auth.example.com/token
                          scopes:
                            orders:read: Read orders
                            orders:write: Write orders
                    ApiKey:
                      type: apiKey
                      in: query
                      name: api_key
                """.trimIndent(),
                "./openapi.yaml",
            ).toFeature()

            assertThat(feature.scenarios.single { it.path == "/or" }.httpRequestPattern.securitySchemes)
                .containsExactly(
                    oauth2Bearer(requiredScopes = listOf("orders:read")),
                    APIKeyInQueryParamSecurityScheme("api_key", null),
                )

            assertThat(feature.scenarios.single { it.path == "/and" }.httpRequestPattern.securitySchemes)
                .containsExactly(
                    CompositeSecurityScheme(
                        listOf(
                            oauth2Bearer(requiredScopes = listOf("orders:write")),
                            APIKeyInQueryParamSecurityScheme("api_key", null),
                        )
                    )
                )

            assertThat(feature.scenarios.single { it.path == "/anonymous" }.httpRequestPattern.securitySchemes)
                .containsExactly(
                    NoSecurityScheme(),
                    oauth2Bearer(requiredScopes = listOf("orders:read")),
                )
        }
    }

    @Nested
    inner class OpenIdConnectMetadataTests {
        @Test
        fun `retains discovery URL and operation required scopes`() {
            val securitySchemes = feature(
                securityScheme = """
                OrdersOIDC:
                  type: openIdConnect
                  openIdConnectUrl: https://auth.example.com/.well-known/openid-configuration
                """.trimIndent(),
                operationSecurity = "- OrdersOIDC: [orders:read]",
            ).scenarios.single().httpRequestPattern.securitySchemes

            assertThat(securitySchemes).containsExactly(
                oidcBearer(requiredScopes = listOf("orders:read"))
            )
        }

        @Test
        fun `keeps OIDC and HTTP bearer as distinct mechanisms`() {
            val schemes = feature(
                securityScheme = """
                OrdersOIDC:
                  type: openIdConnect
                  description: OIDC scheme description
                  openIdConnectUrl: https://auth.example.com/.well-known/openid-configuration
                  x-provider: auth.example.com
                Bearer:
                  type: http
                  scheme: bearer
                """.trimIndent(),
                operationSecurity = """
                - OrdersOIDC: [orders:read]
                - Bearer: []
                """.trimIndent(),
            ).scenarios.single().httpRequestPattern.securitySchemes

            assertThat(schemes).containsExactly(
                oidcBearer(
                    requiredScopes = listOf("orders:read"),
                    description = "OIDC scheme description",
                    extensions = mapOf("x-provider" to "auth.example.com"),
                ),
                BearerSecurityScheme(),
            )
        }
    }

    @Nested
    inner class HttpAndApiKeySchemes {
        @Test
        fun `retains HTTP bearer basic and API key request destinations`() {
            val feature = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.3
                info:
                  title: HTTP and API key authentication
                  version: 1.0.0
                paths:
                  /bearer:
                    get:
                      security: [{ HttpBearer: [] }]
                      responses:
                        '200': { description: OK }
                  /basic:
                    get:
                      security: [{ HttpBasic: [] }]
                      responses:
                        '200': { description: OK }
                  /header-key:
                    get:
                      security: [{ HeaderKey: [] }]
                      responses:
                        '200': { description: OK }
                  /query-key:
                    get:
                      security: [{ QueryKey: [] }]
                      responses:
                        '200': { description: OK }
                components:
                  securitySchemes:
                    HttpBearer:
                      type: http
                      scheme: bearer
                    HttpBasic:
                      type: http
                      scheme: basic
                    HeaderKey:
                      type: apiKey
                      in: header
                      name: X-API-Key
                    QueryKey:
                      type: apiKey
                      in: query
                      name: api_key
                """.trimIndent(),
                "./openapi.yaml",
            ).toFeature()

            assertThat(feature.scenarios.single { it.path == "/bearer" }.httpRequestPattern.securitySchemes)
                .containsExactly(BearerSecurityScheme())
            assertThat(feature.scenarios.single { it.path == "/basic" }.httpRequestPattern.securitySchemes)
                .containsExactly(BasicAuthSecurityScheme())
            assertThat(feature.scenarios.single { it.path == "/header-key" }.httpRequestPattern.securitySchemes)
                .containsExactly(APIKeyInHeaderSecurityScheme("X-API-Key", null))
            assertThat(feature.scenarios.single { it.path == "/query-key" }.httpRequestPattern.securitySchemes)
                .containsExactly(APIKeyInQueryParamSecurityScheme("api_key", null))
        }
    }

    @Nested
    inner class AuthorizationHeaderOverrides {
        @Test
        fun `unconstrained authorization headers retain OAuth security and are removed as ordinary headers`() {
            val requestPattern = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.3
                info:
                  title: OAuth authorization parameter
                  version: 1.0.0
                paths:
                  /resource:
                    get:
                      security: [{ OrdersOAuth: [] }]
                      parameters:
                        - name: Authorization
                          in: header
                          required: true
                          schema:
                            type: string
                      responses:
                        '200': { description: OK }
                components:
                  securitySchemes:
                    OrdersOAuth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: https://auth.example.com/token
                          scopes: {}
                """.trimIndent(),
                "./openapi.yaml",
            ).toFeature().scenarios.single().httpRequestPattern

            assertThat(requestPattern.securitySchemes)
                .containsExactly(
                    oauth2Bearer(
                        flows = oauth2Flows(
                            clientCredentials = OpenApiOAuthFlow(
                                refreshUrl = null,
                                extensions = null,
                                scopes = emptyMap(),
                                authorizationUrl = null,
                                tokenUrl = "https://auth.example.com/token",
                            ),
                        ),
                    )
                )
            assertThat(requestPattern.headersPattern.pattern.keys.map { it.removeSuffix("?") })
                .doesNotContain("Authorization")
        }

        @Test
        fun `constrained authorization header overrides OAuth security`() {
            val requestPattern = OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.3
                info:
                  title: OAuth authorization parameter
                  version: 1.0.0
                paths:
                  /resource:
                    get:
                      security: [{ OrdersOAuth: [] }]
                      parameters:
                        - name: Authorization
                          in: header
                          required: true
                          schema:
                            type: string
                            enum: [CustomCredential]
                      responses:
                        '200': { description: OK }
                components:
                  securitySchemes:
                    OrdersOAuth:
                      type: oauth2
                      flows:
                        clientCredentials:
                          tokenUrl: https://auth.example.com/token
                          scopes: {}
                """.trimIndent(),
                "./openapi.yaml",
            ).toFeature().scenarios.single().httpRequestPattern

            assertThat(requestPattern.securitySchemes).containsExactly(NoSecurityScheme())
            assertThat(requestPattern.headersPattern.pattern.keys.map { it.removeSuffix("?") })
                .containsExactly("Authorization")
        }
    }

    @Nested
    inner class ExistingMatchingBehaviorTests {
        @Test
        fun `request presence identifies destination while matches validates its value`() {
            val placeholder = HttpRequest(headers = mapOf("Authorization" to $$"$specmatic.auth"))
            val bearer = BearerSecurityScheme()
            val basic = BasicAuthSecurityScheme()
            val oauth2 = oauth2Bearer()
            val oidc = oidcBearer()

            assertThat(oidc.isInRequest(placeholder, complete = true)).isTrue()
            assertThat(basic.isInRequest(placeholder, complete = true)).isTrue()
            assertThat(bearer.isInRequest(placeholder, complete = true)).isTrue()
            assertThat(oauth2.isInRequest(placeholder, complete = true)).isTrue()
            assertThat(oidc.matches(placeholder, Resolver())).isInstanceOf(Result.Failure::class.java)
            assertThat(basic.matches(placeholder, Resolver())).isInstanceOf(Result.Failure::class.java)
            assertThat(bearer.matches(placeholder, Resolver())).isInstanceOf(Result.Failure::class.java)
            assertThat(oauth2.matches(placeholder, Resolver())).isInstanceOf(Result.Failure::class.java)

            val bearerValue = HttpRequest(headers = mapOf("Authorization" to "Bearer token"))
            assertThat(oidc.matches(bearerValue, Resolver())).isInstanceOf(Result.Success::class.java)
            assertThat(basic.matches(bearerValue, Resolver())).isInstanceOf(Result.Failure::class.java)
            assertThat(bearer.matches(bearerValue, Resolver())).isInstanceOf(Result.Success::class.java)
            assertThat(oauth2.matches(bearerValue, Resolver())).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `composite presence reports partial and complete destination sets`() {
            val composite = CompositeSecurityScheme(
                listOf(BearerSecurityScheme(), APIKeyInQueryParamSecurityScheme("api_key", null))
            )

            val request = HttpRequest(headers = mapOf("Authorization" to "Bearer token"))
            assertThat(composite.isInRequest(request, complete = false)).isTrue()
            assertThat(composite.isInRequest(request, complete = true)).isFalse()
        }
    }

    private fun feature(securityScheme: String, operationSecurity: String) = OpenApiSpecification.fromYAML(
        """
        openapi: 3.0.3
        info:
          title: Security metadata
          version: 1.0.0
        paths:
          /resource:
            get:
              security:
${operationSecurity.prependIndent("                ")}
              responses:
                '200': { description: OK }
        components:
          securitySchemes:
${securityScheme.prependIndent("            ")}
        """.trimIndent(),
        "",
    ).toFeature()

    private fun oauth2Bearer(
        description: String? = null,
        schemeName: String = "OrdersOAuth",
        extensions: Map<String, Any?>? = null,
        flows: OpenApiOAuthFlows = oauth2Flows(),
        requiredScopes: List<String> = emptyList(),
    ) = BearerSecurityScheme(
        authentication = BearerAuthentication.OAuth2(
            flows = flows,
            schemeName = schemeName,
            extensions = extensions,
            description = description,
            requiredScopes = requiredScopes,
        ),
    )

    private fun oauth2Flows(
        authorizationCode: OpenApiOAuthFlow? = null,
        clientCredentials: OpenApiOAuthFlow? = OpenApiOAuthFlow(
            refreshUrl = null,
            extensions = null,
            authorizationUrl = null,
            tokenUrl = "https://auth.example.com/token",
            scopes = mapOf("orders:read" to "Read orders", "orders:write" to "Write orders"),
        ),
    ) = OpenApiOAuthFlows(
        implicit = null,
        password = null,
        extensions = null,
        clientCredentials = clientCredentials,
        authorizationCode = authorizationCode,
    )

    private fun oidcBearer(
        description: String? = null,
        schemeName: String = "OrdersOIDC",
        extensions: Map<String, Any?>? = null,
        requiredScopes: List<String> = emptyList(),
        openIdConnectUrl: String? = "https://auth.example.com/.well-known/openid-configuration",
    ) = BearerSecurityScheme(
        authentication = BearerAuthentication.OpenIdConnect(
            schemeName = schemeName,
            extensions = extensions,
            description = description,
            requiredScopes = requiredScopes,
            openIdConnectUrl = openIdConnectUrl,
        ),
    )
}
