package `in`.specmatic.conversions

import com.google.common.net.HttpHeaders
import `in`.specmatic.core.*
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.pattern.parsedJSONObject
import `in`.specmatic.core.value.Value
import `in`.specmatic.stub.createStubFromContracts
import `in`.specmatic.test.TestExecutor
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class OpenApiIntegrationTest {
    private val sourceSpecPath = File("src/test/resources/hello.spec").canonicalPath

    @Nested
    inner class Oauth2SecuritySchemeTest {

        @Nested
        inner class AuthorizationCodeFlowTest {

            @Test
            fun `should generate test with oauth2 authorization code security scheme with authorization header value from example`() {
                val contract: Feature = parseGherkinStringToFeature(
                    """
Feature: OAuth2

Background:
Given openapi openapi/hello_with_oauth2_authorization_code_flow.yaml

Scenario: Authorization Code Flow Test
When GET /hello/(id:number)
Then status 200

Examples:
| Authorization | id |
| Bearer abc123 | 10 |
    """.trimIndent(), sourceSpecPath
                )

                val contractTests = contract.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc123")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }

                })

                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should generate test with oauth2 authorization code security scheme with random token in authorization header when no example exists`() {
                val feature = parseContractFileToFeature("./src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml")
                val contractTests = feature.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsKey(HttpHeaders.AUTHORIZATION)
                        assertThat(request.headers[HttpHeaders.AUTHORIZATION]).matches("Bearer (\\S+)")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }

                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should generate test with oauth2 authorization code security scheme with token in authorization header from security configuration`() {
                val token = "QWERTY1234"
                val feature = parseContractFileToFeature(
                    "./src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml",
                    securityConfiguration = newSecurityConfiguration(token)
                )
                val contractTests = feature.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsKey(HttpHeaders.AUTHORIZATION)
                        assertThat(request.headers[HttpHeaders.AUTHORIZATION]).matches("Bearer QWERTY1234")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })

                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should generate test with oauth2 authorization code security scheme with token in authorization header from environment variable`() {
                val token = "ENV1234"
                val environment = mockk<Environment>()
                every { environment.getEnvironmentVariable("oAuth2AuthCode") }.returns(token)
                val feature = parseContractFileToFeature(
                    "./src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml",
                    environment = environment
                )
                val contractTests = feature.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsKey(HttpHeaders.AUTHORIZATION)
                        assertThat(request.headers[HttpHeaders.AUTHORIZATION]).matches("Bearer ENV1234")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })

                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            private fun newSecurityConfiguration(token: String) = SecurityConfiguration(
                OpenAPI = OpenAPISecurityConfiguration(
                    securitySchemes = mapOf(
                        "oAuth2AuthCode" to OAuth2SecuritySchemeConfiguration(
                            "oauth2", token
                        )
                    )
                )
            )

            @Test
            fun `should match http request with authorization header for spec with oauth2 security scheme with authorization code`() {
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml")
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1",
                    mapOf(
                        HttpHeaders.AUTHORIZATION to "Bearer foo"
                    )
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should match http request with authorization header for spec with oauth2 security scheme with token defined in environment variable`() {
                val token = "ENV1234"
                val environment = mockk<Environment>()
                every { environment.getEnvironmentVariable("oAuth2AuthCode") }.returns(token)
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml", environment = environment)
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1",
                    mapOf(
                        HttpHeaders.AUTHORIZATION to "Bearer $token"
                    )
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should not match http request with authorization header missing for spec with oauth2 authorization code security scheme with authorization code`() {
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml")
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1"
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Failure::class.java)
                assertThat(result.reportString()).contains("Authorization header is missing in request")
            }

            @Test
            fun `should not match http request with authorization header without bearer prefix for spec with oauth2 authorization code security scheme with authorization code`() {
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_authorization_code_flow.yaml")
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1",
                    mapOf(
                        HttpHeaders.AUTHORIZATION to "foo"
                    )
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Failure::class.java)
                assertThat(result.reportString()).contains("Authorization header must be prefixed with \"Bearer\"")
            }

        }

        @Nested
        inner class ClientCredentialsFlowTest {

            @Test
            fun `should generate test with oauth2 client credentials security scheme with authorization header value from example`() {
                val contract: Feature = parseGherkinStringToFeature(
                    """
Feature: Authenticated

  Background:
    Given openapi openapi/hello_with_oauth2_client_credentials_flow.yaml
  
  Scenario: OAuth2 Client Credentials Flow Test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | Authorization | id |
    | Bearer abc123 | 10 |
        """.trimIndent(), sourceSpecPath
                )
                val contractTests = contract.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc123")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }

                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should match http request with authorization header for spec with oauth2 security scheme with client credentials flow`() {
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_client_credentials_flow.yaml")
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1",
                    mapOf(
                        HttpHeaders.AUTHORIZATION to "Bearer foo"
                    )
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

        }

        @Nested
        inner class ImplicitFlowTest {

            @Test
            fun `should generate test with oauth2 implicit security scheme with authorization header value from example`() {
                val contract: Feature = parseGherkinStringToFeature(
                    """
Feature: Authenticated

  Background:
    Given openapi openapi/hello_with_oauth2_implicit_flow.yaml
  
  Scenario: OAuth2 Client Credentials Flow Test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | Authorization | id |
    | Bearer abc123 | 10 |
        """.trimIndent(), sourceSpecPath
                )
                val contractTests = contract.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc123")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }

                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should match http request with authorization header for spec with oauth2 security scheme with implicit flow`() {
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_implicit_flow.yaml")
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1",
                    mapOf(
                        HttpHeaders.AUTHORIZATION to "Bearer foo"
                    )
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

        }

        @Nested
        inner class ResourceOwnerPasswordCredentialsFlowTest {

            @Test
            fun `should generate test with oauth2 resource owner password credentials security scheme with authorization header value from example`() {
                val contract: Feature = parseGherkinStringToFeature(
                    """
Feature: Authenticated

  Background:
    Given openapi openapi/hello_with_oauth2_resource_owner_password_credentials_flow.yaml
  
  Scenario: OAuth2 Client Credentials Flow Test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | Authorization | id |
    | Bearer abc123 | 10 |
        """.trimIndent(), sourceSpecPath
                )
                val contractTests = contract.generateContractTestScenarios(emptyList())
                val result = executeTest(contractTests.single(), object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        assertThat(request.headers).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer abc123")
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }

                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

            @Test
            fun `should match http request with authorization header for spec with oauth2 security scheme with resource owner password credentials flow`() {
                val feature = parseContractFileToFeature("src/test/resources/openapi/hello_with_oauth2_resource_owner_password_credentials_flow.yaml")
                val httpRequest = HttpRequest(
                    "GET",
                    "/hello/1",
                    mapOf(
                        HttpHeaders.AUTHORIZATION to "Bearer foo"
                    )
                )
                val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }

        }
    }

    @Nested
    inner class BearerSecuritySchemeTest {
        @Test
        fun `should generate test with bearer security scheme with authorization header value from example`() {
            val contract: Feature = parseGherkinStringToFeature(
                """
Feature: Authenticated

  Background:
    Given openapi openapi/authenticated.yaml
  
  Scenario: Bearer auth test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | Authorization | id |
    | Bearer abc123 | 10 |
        """.trimIndent(), sourceSpecPath
            )

            val contractTests = contract.generateContractTestScenarios(emptyList())
            val result = executeTest(contractTests.single(), object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers).containsEntry("Authorization", "Bearer abc123")
                    return HttpResponse.OK("success")
                }

                override fun setServerState(serverState: Map<String, Value>) {

                }

            })

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should generate test with bearer security scheme with random token in authorization header when no example exists`() {
            val feature = parseContractFileToFeature("./src/test/resources/openapi/authenticated.yaml")
            val contractTests = feature.generateContractTestScenarios(emptyList())
            var requestMadeWithRandomlyGeneratedBearerToken = false
            contractTests.forEach { scenario ->
                val result = executeTest(scenario, object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        request.headers[HttpHeaders.AUTHORIZATION]?.takeIf {
                            it.matches(Regex("Bearer (\\S+)"))
                        }?.let {
                            requestMadeWithRandomlyGeneratedBearerToken = true
                        }
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }
            assertThat(requestMadeWithRandomlyGeneratedBearerToken).isTrue
        }

        @Test
        fun `should generate test with bearer security scheme with token in authorization header from security configuration`() {
            val token = "TOKEN1234"
            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                securityConfiguration = securityConfigurationForBearerScheme(token)
            )
            val contractTests = feature.generateContractTestScenarios(emptyList())
            var requestMadeWithTokenFromSpecmaticJson = false
            contractTests.forEach { scenario ->
                val result = executeTest(scenario, object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        request.headers[HttpHeaders.AUTHORIZATION]?.takeIf {
                            it == "Bearer TOKEN1234"
                        }?.let {
                            requestMadeWithTokenFromSpecmaticJson = true
                        }
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }
            assertThat(requestMadeWithTokenFromSpecmaticJson).isTrue
        }

        @Test
        fun `should generate test with bearer security scheme with token in authorization header from environment variable`() {
            val token = "ENV1234"
            val environment = mockk<Environment>()
            every { environment.getEnvironmentVariable("BearerAuth") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthQuery") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthHeader") }.returns(token)
            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                environment = environment
            )
            val contractTests = feature.generateContractTestScenarios(emptyList())
            var requestMadeWithTokenFromSpecmaticJson = false
            contractTests.forEach { scenario ->
                val result = executeTest(scenario, object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        request.headers[HttpHeaders.AUTHORIZATION]?.takeIf {
                            it == "Bearer ENV1234"
                        }?.let {
                            requestMadeWithTokenFromSpecmaticJson = true
                        }
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }
            assertThat(requestMadeWithTokenFromSpecmaticJson).isTrue
        }

        @Test
        fun `should match http request with authorization header for spec with bearer security scheme`() {
            val feature = parseGherkinStringToFeature(
                """
Feature: Hello world

Background:
  Given openapi openapi/hello.yaml
        """.trimIndent(), sourceSpecPath
            )
            val httpRequest = HttpRequest(
                "GET",
                "/hello/1",
                mapOf(
                    HttpHeaders.AUTHORIZATION to "Bearer foo"
                )
            )
            val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should match http request with authorization header for spec with bearer security scheme with token defined in environment variable`() {
            val token = "ENV1234"
            val environment = mockk<Environment>()
            every { environment.getEnvironmentVariable("BearerAuth") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthQuery") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthHeader") }.returns(token)
            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                environment = environment
            )
            val httpRequest = HttpRequest(
                "GET",
                "/hello/1",
                mapOf(
                    HttpHeaders.AUTHORIZATION to "Bearer foo"
                )
            )
            val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should not match http request without authorization header for spec with bearer security scheme`() {
            val feature = parseGherkinStringToFeature(
                """
Feature: Hello world

Background:
  Given openapi openapi/hello.yaml
        """.trimIndent(), sourceSpecPath
            )
            val httpRequest = HttpRequest(
                "GET",
                "/hello/1"
            )
            val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).contains("Authorization header is missing in request")
        }

        @Test
        fun `should not match http request with authorization header but without the bearer prefix for spec with bearer security scheme`() {
            val feature = parseGherkinStringToFeature(
                """
Feature: Hello world

Background:
  Given openapi openapi/hello.yaml
        """.trimIndent(), sourceSpecPath
            )
            val httpRequest = HttpRequest(
                "GET",
                "/hello/1",
                mapOf(
                    HttpHeaders.AUTHORIZATION to "foo"
                )
            )
            val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).contains("Authorization header must be prefixed with \"Bearer\"")
        }

        private fun securityConfigurationForBearerScheme(token: String) = SecurityConfiguration(
            OpenAPI = OpenAPISecurityConfiguration(
                securitySchemes = mapOf(
                    "BearerAuth" to BearerSecuritySchemeConfiguration(
                        "bearer", token
                    )
                )
            )
        )

    }

    @Nested
    inner class ApiKeySecuritySchemeTest {
        @Test
        fun `should generate test with query param api key auth security scheme value from row`() {
            val contract: Feature = parseGherkinStringToFeature(
                """
Feature: Authenticated

  Background:
    Given openapi openapi/authenticated.yaml
  
  Scenario: Query param auth test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | apiKey | id |
    | abc123 | 10 |
        """.trimIndent(), sourceSpecPath
            )

            val contractTests = contract.generateContractTestScenarios(emptyList())
            val result = executeTest(contractTests.single(), object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.queryParams).containsEntry("apiKey", "abc123")
                    return HttpResponse.OK("success")
                }

                override fun setServerState(serverState: Map<String, Value>) {

                }

            })

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should throw not supported error when a security scheme other than oauth2 bearer or api key is defined`() {
            assertThrows<ContractException> {
                parseGherkinStringToFeature(
                    """
Feature: Hello world

Background:
  Given openapi openapi/unsupported-authentication.yaml
        """.trimIndent(), sourceSpecPath
                )
            }.also { assertThat(it.message).isEqualTo("Specmatic only supports oauth2, bearer, and api key authentication (header, query) security schemes at the moment") }
        }

        @Test
        fun `should throw not supported error when a non-query-or-header API security scheme is defined`() {
            assertThrows<ContractException> {
                parseGherkinStringToFeature(
                    """
Feature: Hello world

Background:
  Given openapi openapi/apiKeyAuthCookie.yaml
        """.trimIndent(), sourceSpecPath
                )
            }.also { assertThat(it.message).isEqualTo("Specmatic only supports oauth2, bearer, and api key authentication (header, query) security schemes at the moment") }
        }

        @Test
        fun `should generate test with api key security scheme value from row`() {
            val contract: Feature = parseGherkinStringToFeature(
                """
Feature: Authenticated

  Background:
    Given openapi openapi/authenticated.yaml
  
  Scenario: Header auth test
    When GET /hello/(id:number)
    Then status 200
    
    Examples:
    | X-API-KEY | id |
    | abc123    | 10 |
        """.trimIndent(), sourceSpecPath
            )

            val contractTests = contract.generateContractTestScenarios(emptyList())
            val result = executeTest(contractTests.single(), object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(request.headers).containsEntry("X-API-KEY", "abc123")
                    return HttpResponse.OK("success")
                }

                override fun setServerState(serverState: Map<String, Value>) {

                }

            })

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `should generate test with api key in header security scheme with token in header from security configuration`() {
            val token = "APIHEADERKEY1234"
            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                securityConfiguration = securityConfigurationForApiKeyInHeaderScheme(token)
            )
            val contractTests = feature.generateContractTestScenarios(emptyList())
            var requestMadeWithApiKeyInHeaderFromSpecmaticJson = false
            contractTests.forEach { scenario ->
                val result = executeTest(scenario, object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        request.headers["X-API-KEY"]?.takeIf {
                            it == "APIHEADERKEY1234"
                        }?.let {
                            requestMadeWithApiKeyInHeaderFromSpecmaticJson = true
                        }
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }
            assertThat(requestMadeWithApiKeyInHeaderFromSpecmaticJson).isTrue
        }

        @Test
        fun `should generate test with api key in header security scheme with token in header from environment variable`() {
            val token = "ENV1234"
            val environment = mockk<Environment>()
            every { environment.getEnvironmentVariable("ApiKeyAuthHeader") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthQuery") }.returns(token)
            every { environment.getEnvironmentVariable("BearerAuth") }.returns(token)

            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                environment = environment
            )
            val contractTests = feature.generateContractTestScenarios(emptyList())
            var requestMadeWithApiKeyInHeaderFromSpecmaticJson = false
            contractTests.forEach { scenario ->
                val result = executeTest(scenario, object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        request.headers["X-API-KEY"]?.takeIf {
                            it == "ENV1234"
                        }?.let {
                            requestMadeWithApiKeyInHeaderFromSpecmaticJson = true
                        }
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }
            assertThat(requestMadeWithApiKeyInHeaderFromSpecmaticJson).isTrue
        }

        @Test
        fun `should generate test with api key in query param security scheme with token in query param from security configuration`() {
            val token = "APIQUERYKEY1234"
            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                securityConfiguration = securityConfigurationForApiKeyInQueryScheme(token)
            )
            val contractTests = feature.generateContractTestScenarios(emptyList())
            var requestMadeWithApiKeyInQueryFromSpecmaticJson = false
            contractTests.forEach { scenario ->
                val result = executeTest(scenario, object : TestExecutor {
                    override fun execute(request: HttpRequest): HttpResponse {
                        request.queryParams["apiKey"]?.takeIf {
                            it == "APIQUERYKEY1234"
                        }?.let {
                            requestMadeWithApiKeyInQueryFromSpecmaticJson = true
                        }
                        return HttpResponse.OK("success")
                    }

                    override fun setServerState(serverState: Map<String, Value>) {

                    }
                })
                assertThat(result).isInstanceOf(Result.Success::class.java)
            }
            assertThat(requestMadeWithApiKeyInQueryFromSpecmaticJson).isTrue
        }

        @Test
        fun `should generate stub that authenticates with api key in header and query`() {
            createStubFromContracts(listOf("src/test/resources/openapi/apiKeyAuth.yaml")).use {
                val requestWithHeader = HttpRequest(
                    method = "GET",
                    path = "/hello/10",
                    headers = mapOf(
                        "X-API-KEY" to "test"
                    )
                )

                val responseFromHeader = it.client.execute(requestWithHeader)
                assertThat(responseFromHeader.status).isEqualTo(200)

                val requestWithQuery = HttpRequest(
                    method = "GET",
                    path = "/hello/10",
                    queryParams = mapOf(
                        "apiKey" to "test"
                    )
                )

                val responseFromQuery = it.client.execute(requestWithQuery)
                assertThat(responseFromQuery.status).isEqualTo(200)
            }
        }

        @Test
        fun `should match http request with authorization header for spec with apikey security scheme in header with token defined in environment variable`() {
            val token = "ENV1234"
            val environment = mockk<Environment>()
            every { environment.getEnvironmentVariable("BearerAuth") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthQuery") }.returns(token)
            every { environment.getEnvironmentVariable("ApiKeyAuthHeader") }.returns(token)
            val feature = parseContractFileToFeature(
                "./src/test/resources/openapi/authenticated.yaml",
                environment = environment
            )
            val httpRequest = HttpRequest(
                "GET",
                "/hello/1",
                mapOf(
                    HttpHeaders.AUTHORIZATION to "Bearer ENV1234"
                )
            )
            val result = feature.scenarios.first().httpRequestPattern.matches(httpRequest, Resolver())
            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        private fun securityConfigurationForApiKeyInHeaderScheme(token: String) = SecurityConfiguration(
            OpenAPI = OpenAPISecurityConfiguration(
                securitySchemes = mapOf(
                    "ApiKeyAuthHeader" to APIKeySecuritySchemeConfiguration(
                        "apiKey", token
                    )
                )
            )
        )

        private fun securityConfigurationForApiKeyInQueryScheme(token: String) = SecurityConfiguration(
            OpenAPI = OpenAPISecurityConfiguration(
                securitySchemes = mapOf(
                    "ApiKeyAuthQuery" to APIKeySecuritySchemeConfiguration(
                        "apiKey", token
                    )
                )
            )
        )
    }

    @ParameterizedTest
    @CsvSource(
        value = [
        """pass | {"name": "John", "surname": "James"}""",
        """fail | {}""",
        """fail | {"name": "John", "surname": "James", "fathers_name": "James"}""",
        ],
        delimiter = '|',
        ignoreLeadingAndTrailingWhitespace = true
    )
    fun `minProperties and maxProperties should be honored`(expectedResult: String, requestBody: String) {
        val yamlContent = """
            openapi: 3.0.1
            info:
              title: API
              version: 1
            paths:
              /name:
                post:
                  summary: Post name
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            name:
                              type: string
                            surname:
                              type: string
                            fathers_name:
                              type: string
                          minProperties: 1
                          maxProperties: 2
                          required:
                            - name
                  responses:
                    '200':
                      description: Successful response
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              message:
                                type: string
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(yamlContent, "").toFeature()
        val result = feature.scenarios.first().httpRequestPattern.matches(
            HttpRequest(
                "POST",
                "/name",
                body = parsedJSONObject(requestBody)
            ), Resolver()
        )

        when(expectedResult) {
            "pass" -> assertThat(result).isInstanceOf(Result.Success::class.java)
            "fail" -> assertThat(result).isInstanceOf(Result.Failure::class.java)
        }
    }
}
