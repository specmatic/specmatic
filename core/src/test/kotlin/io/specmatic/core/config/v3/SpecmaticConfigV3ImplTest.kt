package io.specmatic.core.config.v3

import io.specmatic.core.ApplicationApiSource
import io.specmatic.core.Configuration
import io.specmatic.core.DEFAULT_SWAGGER_SPEC_YAML_PATH
import io.specmatic.core.KeyData
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.SourceProvider
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.toSpecmaticConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.utilities.FileAssociation
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.security.KeyStore

class SpecmaticConfigV3ImplTest {
    @TempDir
    lateinit var tempDir: File

    @ParameterizedTest
    @MethodSource("loadSourcesTestCases")
    fun `should return similar values between v2 and v3 loadSources`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("externalSpecTypeConfigs")
    fun `should return similar values between v2 and v3 configFor`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("testSettings")
    fun `should return similar values between v2 and v3 test settings`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("stubSettings")
    fun `should return similar values between v2 and v3 stub settings`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("generalSettings")
    fun `should return similar values between v2 and v3 general settings`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("stubData")
    fun `should return similar values between v2 and v3 stub data`(testCase: TestCase) = testCase.run(tempDir)

    @ParameterizedTest
    @MethodSource("testData")
    fun `should return similar values between v2 and v3 test data`(testCase: TestCase) = testCase.run(tempDir)

    @Test
    fun `should not resolve swagger url from an arbitrary openapi run options spec`() {
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - spec:
                            id: api-spec
                            path: api.yaml
                runOptions:
                  openapi:
                    specs:
                      - spec:
                          id: api-spec
                          swaggerUrl: http://localhost:8080/v3/api-docs
            """.trimIndent()
        )

        assertThat(config.getTestSwaggerUrl()).isNull()
    }

    @Test
    fun `should prefer openapi run options swagger url over spec swagger url`() {
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - spec:
                            id: api-spec
                            path: api.yaml
                runOptions:
                  openapi:
                    swaggerUrl: http://localhost:8080/top-level-api-docs
                    specs:
                      - spec:
                          id: api-spec
                          swaggerUrl: http://localhost:8080/spec-api-docs
            """.trimIndent()
        )

        assertThat(config.getTestSwaggerUrl()).isEqualTo("http://localhost:8080/top-level-api-docs")
    }

    @Test
    fun `should use matching spec application api sources before top level sources`() {
        val specDir = tempDir.resolve("spec-application-api-sources").apply { mkdirs() }
        val swaggerSpec = specDir.resolve("swagger.yaml").apply { writeText("openapi: 3.0.0") }
        val swaggerUiSpec = specDir.resolve("swagger-ui.yaml").apply { writeText("openapi: 3.0.0") }
        val baseUrlSpec = specDir.resolve("base-url.yaml").apply { writeText("openapi: 3.0.0") }
        val hostAndPortSpec = specDir.resolve("host-and-port.yaml").apply { writeText("openapi: 3.0.0") }
        val portOnlySpec = specDir.resolve("port-only.yaml").apply { writeText("openapi: 3.0.0") }
        val actuatorSpec = specDir.resolve("actuator.yaml").apply { writeText("openapi: 3.0.0") }
        val hostOnlySpec = specDir.resolve("host-only.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - spec:
                            id: swagger
                            path: swagger.yaml
                        - spec:
                            id: swagger-ui
                            path: swagger-ui.yaml
                        - spec:
                            id: base-url
                            path: base-url.yaml
                        - spec:
                            id: host-and-port
                            path: host-and-port.yaml
                        - spec:
                            id: port-only
                            path: port-only.yaml
                        - spec:
                            id: actuator
                            path: actuator.yaml
                        - spec:
                            id: host-only
                            path: host-only.yaml
                runOptions:
                  openapi:
                    actuatorUrl: http://top.example/actuator
                    swaggerUrl: http://top.example/openapi.yaml
                    swaggerUiBaseUrl: http://top.example/swagger-ui
                    specs:
                      - spec:
                          id: swagger
                          swaggerUrl: http://swagger.example/openapi.yaml
                      - spec:
                          id: swagger-ui
                          swaggerUiBaseUrl: http://swagger-ui.example
                      - spec:
                          id: base-url
                          baseUrl: http://base-url.example
                      - spec:
                          id: host-and-port
                          host: host-and-port.example
                          port: 8083
                      - spec:
                          id: port-only
                          port: 8084
                      - spec:
                          id: actuator
                          actuatorUrl: http://actuator.example/mappings
                      - spec:
                          id: host-only
                          host: host-only.example
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(swaggerSpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.Swagger("http://swagger.example/openapi.yaml"))
        assertThat(config.getTestApplicationApiSource(swaggerUiSpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.SwaggerUi("http://swagger-ui.example"))
        assertThat(config.getTestApplicationApiSource(baseUrlSpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.Swagger("http://base-url.example$DEFAULT_SWAGGER_SPEC_YAML_PATH", isExplicitlyConfigured = false))
        assertThat(config.getTestApplicationApiSource(hostAndPortSpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.Swagger("http://host-and-port.example:8083$DEFAULT_SWAGGER_SPEC_YAML_PATH", isExplicitlyConfigured = false))
        assertThat(config.getTestApplicationApiSource(portOnlySpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:8084$DEFAULT_SWAGGER_SPEC_YAML_PATH", isExplicitlyConfigured = false))
        assertThat(config.getTestApplicationApiSource(actuatorSpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.Actuator("http://actuator.example/mappings"))
        assertThat(config.getTestApplicationApiSource(hostOnlySpec, SpecType.OPENAPI, "http://runtime.example"))
            .isEqualTo(ApplicationApiSource.Actuator("http://top.example/actuator"))
    }

    @Test
    fun `should not return spec application api sources from top level getters`() {
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - spec:
                            id: api
                            path: api.yaml
                runOptions:
                  openapi:
                    specs:
                      - spec:
                          id: api
                          actuatorUrl: http://spec.example/actuator
                          swaggerUiBaseUrl: http://spec.example/swagger-ui
            """.trimIndent()
        )

        assertThat(config.getActuatorUrl()).isNull()
        assertThat(config.getTestSwaggerUIBaseUrl()).isNull()
    }

    @Test
    fun `should resolve matching spec swagger url before top level actuator`() {
        val specDir = tempDir.resolve("specs").apply { mkdirs() }
        val orderSpec = specDir.resolve("order.yaml").apply { writeText("openapi: 3.0.0") }
        val cartSpec = specDir.resolve("cart.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - spec:
                            id: order
                            path: order.yaml
                        - spec:
                            id: cart
                            path: cart.yaml
                runOptions:
                  openapi:
                    actuatorUrl: http://localhost:8080/actuator
                    swaggerUrl: http://localhost:8080/default-api-docs
                    swaggerUiBaseUrl: http://localhost:8080/swagger-ui
                    specs:
                      - spec:
                          id: order
                          swaggerUrl: http://localhost:9090/order-and-cart-api-docs
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(orderSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:9090/order-and-cart-api-docs"))
        assertThat(config.getTestApplicationApiSource(cartSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Actuator("http://localhost:8080/actuator"))
    }

    @Test
    fun `should resolve application api source using spec swagger url before openapi defaults`() {
        val specDir = tempDir.resolve("spec-swagger-url").apply { mkdirs() }
        val orderSpec = specDir.resolve("order.yaml").apply { writeText("openapi: 3.0.0") }
        val cartSpec = specDir.resolve("cart.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - spec:
                            id: order
                            path: order.yaml
                        - spec:
                            id: cart
                            path: cart.yaml
                runOptions:
                  openapi:
                    swaggerUrl: http://localhost:8080/default-api-docs
                    swaggerUiBaseUrl: http://localhost:8080/swagger-ui
                    specs:
                      - spec:
                          id: order
                          swaggerUrl: http://localhost:9090/order-and-cart-api-docs
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(orderSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:9090/order-and-cart-api-docs"))
        assertThat(config.getTestApplicationApiSource(cartSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:8080/default-api-docs"))
    }

    @Test
    fun `should derive application api source from spec base url before openapi swagger url`() {
        val specDir = tempDir.resolve("spec-base-url").apply { mkdirs() }
        val orderSpec = specDir.resolve("order.yaml").apply { writeText("openapi: 3.0.0") }
        val cartSpec = specDir.resolve("cart.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - spec:
                            id: order
                            path: order.yaml
                        - spec:
                            id: cart
                            path: cart.yaml
                runOptions:
                  openapi:
                    swaggerUrl: http://localhost:8080/default-api-docs
                    specs:
                      - spec:
                          id: order
                          baseUrl: http://localhost:9090/
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(orderSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:9090$DEFAULT_SWAGGER_SPEC_YAML_PATH", isExplicitlyConfigured = false))
        assertThat(config.getTestApplicationApiSource(cartSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:8080/default-api-docs"))
    }

    @Test
    fun `should resolve application api sources using spec swagger url overrides and openapi default`() {
        val specDir = tempDir.resolve("spec-swagger-url-overrides").apply { mkdirs() }
        val orderSpec = specDir.resolve("order.yaml").apply { writeText("openapi: 3.0.0") }
        val cartSpec = specDir.resolve("cart.yaml").apply { writeText("openapi: 3.0.0") }
        val customerSpec = specDir.resolve("customer.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - spec:
                            id: order
                            path: order.yaml
                        - spec:
                            id: cart
                            path: cart.yaml
                        - spec:
                            id: customer
                            path: customer.yaml
                runOptions:
                  openapi:
                    swaggerUrl: http://localhost:9000/v3/api-docs/customer
                    specs:
                      - spec:
                          id: order
                          swaggerUrl: http://localhost:9090/v3/api-docs/order
                      - spec:
                          id: cart
                          swaggerUrl: http://localhost:9010/v3/api-docs/cart
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(orderSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:9090/v3/api-docs/order"))
        assertThat(config.getTestApplicationApiSource(cartSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:9010/v3/api-docs/cart"))
        assertThat(config.getTestApplicationApiSource(customerSpec, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Swagger("http://localhost:9000/v3/api-docs/customer"))
    }

    @Test
    fun `should resolve application api source by source type priority when urls match`() {
        val specDir = tempDir.resolve("same-url-specs").apply { mkdirs() }
        val specFile = specDir.resolve("api.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - api.yaml
                runOptions:
                  openapi:
                    actuatorUrl: http://localhost:8080/apis
                    swaggerUrl: http://localhost:8080/apis
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(specFile, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.Actuator("http://localhost:8080/apis"))
    }

    @Test
    fun `should resolve application api source using swagger UI base url before fallback`() {
        val specDir = tempDir.resolve("swagger-ui-fallback").apply { mkdirs() }
        val specFile = specDir.resolve("api.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - api.yaml
                runOptions:
                  openapi:
                    swaggerUiBaseUrl: http://localhost:8080/swagger-ui
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(specFile, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.SwaggerUi("http://localhost:8080/swagger-ui"))
    }

    @Test
    fun `should resolve application api source using fallback swagger UI base url`() {
        val specDir = tempDir.resolve("fallback-swagger-ui").apply { mkdirs() }
        val specFile = specDir.resolve("api.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - api.yaml
                runOptions:
                  openapi:
            """.trimIndent()
        )

        assertThat(config.getTestApplicationApiSource(specFile, SpecType.OPENAPI, "http://localhost:9000"))
            .isEqualTo(ApplicationApiSource.SwaggerUi("http://localhost:9000", isExplicitlyConfigured = false))
    }

    @Test
    fun `should resolve application api source using endpointsAPI property before swagger sources`() {
        val specDir = tempDir.resolve("endpoints-api-property").apply { mkdirs() }
        val specFile = specDir.resolve("api.yaml").apply { writeText("openapi: 3.0.0") }
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ${specDir.canonicalPath}
                      specs:
                        - api.yaml
                runOptions:
                  openapi:
                    swaggerUrl: http://localhost:8080/apis
            """.trimIndent()
        )

        System.setProperty("endpointsAPI", "http://localhost:8080/actuator")
        try {
            assertThat(config.getTestApplicationApiSource(specFile, SpecType.OPENAPI, "http://localhost:9000"))
                .isEqualTo(ApplicationApiSource.Actuator("http://localhost:8080/actuator"))
        } finally {
            System.clearProperty("endpointsAPI")
        }
    }

    @Test
    fun `should resolve test certificate from v3 run options`() {
        val config = v3Config(
            """
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - simple.yaml
                runOptions:
                  openapi:
                    baseUrl: https://api.example.com:9443
                    cert:
                      keyStore:
                        file: ./client-cert.jks
                      keyStorePassword: password
            """.trimIndent()
        )

        val keyDataRegistry = config.getTestHttpsConfiguration().toKeyDataRegistry {
            KeyData(emptyKeyStore(), "password")
        }

        assertThat(keyDataRegistry.get("api.example.com", 9443)).isNotNull()
    }

    @Test
    fun `should resolve mTLS setting from v3 mock run options cert`() {
        val config = v3Config(
            """
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: order-api
                                path: order.yaml
                    runOptions:
                      openapi:
                        host: localhost
                        port: 9443
                        cert:
                          mtlsEnabled: true
                          keyStore:
                            file: ./server-cert.jks
                          keyStorePassword: password
            """.trimIndent()
        )

        val incomingMtlsRegistry = config.getStubHttpsConfiguration().toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("localhost", 9443)).isTrue()
    }

    @Test
    fun `should resolve mTLS setting from referenced v3 mock run options cert`() {
        val config = v3Config(
            """
            version: 3
            components:
              runOptions:
                mtlsMock:
                  openapi:
                    type: mock
                    host: localhost
                    port: 9443
                    cert:
                      mtlsEnabled: true
                      keyStore:
                        file: ./server-cert.jks
                      keyStorePassword: password
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: order-api
                                path: order.yaml
                    runOptions:
                      ${'$'}ref: "#/components/runOptions/mtlsMock"
            """.trimIndent()
        )

        val incomingMtlsRegistry = config.getStubHttpsConfiguration().toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("localhost", 9443)).isTrue()
    }

    @Test
    fun `should resolve mTLS setting from v3 wsdl mock run options cert`() {
        val config = v3Config(
            """
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: payment-wsdl
                                path: payment.wsdl
                    runOptions:
                      wsdl:
                        host: localhost
                        port: 9450
                        cert:
                          mtlsEnabled: true
                          keyStore:
                            file: ./server-cert.jks
                          keyStorePassword: password
            """.trimIndent()
        )

        val incomingMtlsRegistry = config.getStubHttpsConfiguration().toIncomingMtlsRegistry()

        assertThat(incomingMtlsRegistry.get("localhost", 9450)).isTrue()
    }

    @Nested
    inner class CtrfSpecConfigForTests {
        @Test
        fun `getCtrfSpecConfig should resolve filesystem spec path relative to git repo root when the source directory is the specmatic yaml directory`() {
            val repoRoot = tempDir.resolve("repo-v3").apply { mkdirs() }
            runGit(repoRoot, "init")
            val specFile =
                repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                    parentFile.mkdirs()
                    writeText(minimalOpenApi())
                }
            val configFile = repoRoot.resolve("specmatic.yaml").apply { writeText("version: 3") }
            val originalConfigPath = Configuration.configFilePath

            try {
                Configuration.configFilePath = configFile.canonicalPath
                val config =
                    SpecmaticConfigV3Impl(
                        file = configFile,
                        specmaticConfig = SpecmaticConfigV3(
                            version = io.specmatic.core.config.SpecmaticConfigVersion.VERSION_3,
                            systemUnderTest = testServiceConfig(
                                source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = ".")),
                                configuredSpecPath = "specs/openapi/order_api.yaml"
                            )
                        )
                    )

                val ctrfSpecConfig = config.getCtrfSpecConfig(
                    specFile = specFile,
                    testType = io.specmatic.test.TestResultRecord.CONTRACT_TEST_TEST_TYPE,
                    protocol = "HTTP",
                    specType = SpecType.OPENAPI.value
                )

                assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
                assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
                assertThat(ctrfSpecConfig.repository).isNull()
            } finally {
                Configuration.configFilePath = originalConfigPath
            }
        }

        @Test
        fun `getCtrfSpecConfig should resolve filesystem spec path relative to git repo root when the source directory is explicitly set`() {
            val repoRoot = tempDir.resolve("repo-v3-specs").apply { mkdirs() }
            runGit(repoRoot, "init")
            val specFile =
                repoRoot.resolve("specs/openapi/order_api.yaml").apply {
                    parentFile.mkdirs()
                    writeText(minimalOpenApi())
                }
            val configFile = repoRoot.resolve("specmatic.yaml").apply { writeText("version: 3") }
            val originalConfigPath = Configuration.configFilePath

            try {
                Configuration.configFilePath = configFile.canonicalPath
                val config =
                    SpecmaticConfigV3Impl(
                        file = configFile,
                        specmaticConfig = SpecmaticConfigV3(
                            version = io.specmatic.core.config.SpecmaticConfigVersion.VERSION_3,
                            systemUnderTest = testServiceConfig(
                                source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = "specs")),
                                configuredSpecPath = "openapi/order_api.yaml"
                            )
                        )
                    )

                val ctrfSpecConfig = config.getCtrfSpecConfig(
                    specFile = specFile,
                    testType = io.specmatic.test.TestResultRecord.CONTRACT_TEST_TEST_TYPE,
                    protocol = "HTTP",
                    specType = SpecType.OPENAPI.value
                )

                assertThat(ctrfSpecConfig.specification).isEqualTo("specs/openapi/order_api.yaml")
                assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.filesystem.name)
                assertThat(ctrfSpecConfig.repository).isNull()
            } finally {
                Configuration.configFilePath = originalConfigPath
            }
        }

        @Test
        fun `getCtrfSpecConfig should leave web spec paths unchanged`() {
            val repoRoot = tempDir.resolve("repo-v3-web").apply { mkdirs() }
            runGit(repoRoot, "init")
            val specFile =
                repoRoot.resolve(".specmatic/web/example.com/openapi/order_api.yaml").apply {
                    parentFile.mkdirs()
                    writeText(minimalOpenApi())
                }
            val configFile = repoRoot.resolve("specmatic.yaml").apply { writeText("version: 3") }
            val originalConfigPath = Configuration.configFilePath

            try {
                Configuration.configFilePath = configFile.canonicalPath
                val config =
                    SpecmaticConfigV3Impl(
                        file = configFile,
                        specmaticConfig = SpecmaticConfigV3(
                            version = io.specmatic.core.config.SpecmaticConfigVersion.VERSION_3,
                            systemUnderTest = testServiceConfig(
                                source = SourceV3.create(web = SourceV3.Web(url = "https://example.com")),
                                configuredSpecPath = "openapi/order_api.yaml"
                            )
                        )
                    )

                val ctrfSpecConfig = config.getCtrfSpecConfig(
                    specFile = specFile,
                    testType = io.specmatic.test.TestResultRecord.CONTRACT_TEST_TEST_TYPE,
                    protocol = "HTTP",
                    specType = SpecType.OPENAPI.value
                )

                assertThat(ctrfSpecConfig.specification).isEqualTo("openapi/order_api.yaml")
                assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.web.name)
            } finally {
                Configuration.configFilePath = originalConfigPath
            }
        }

        @Test
        fun `getCtrfSpecConfig should leave git spec paths unchanged`() {
            val repoRoot = tempDir.resolve("repo-v3-git").apply { mkdirs() }
            runGit(repoRoot, "init")
            val configFile = repoRoot.resolve("specmatic.yaml").apply { writeText("version: 3") }
            val originalConfigPath = Configuration.configFilePath

            try {
                Configuration.configFilePath = configFile.canonicalPath
                val configuredSpecPath = "openapi/order_api.yaml"
                val gitSource = SourceV3.create(git = SourceV3.Git(url = "https://example.com/contracts.git"))
                val specFile =
                    gitSource.resolveSpecification(File(configuredSpecPath)).apply {
                        parentFile.mkdirs()
                        writeText(minimalOpenApi())
                    }
                val config =
                    SpecmaticConfigV3Impl(
                        file = configFile,
                        specmaticConfig = SpecmaticConfigV3(
                            version = io.specmatic.core.config.SpecmaticConfigVersion.VERSION_3,
                            systemUnderTest = testServiceConfig(
                                source = gitSource,
                                configuredSpecPath = configuredSpecPath
                            )
                        )
                    )

                val ctrfSpecConfig = config.getCtrfSpecConfig(
                    specFile = specFile,
                    testType = io.specmatic.test.TestResultRecord.CONTRACT_TEST_TEST_TYPE,
                    protocol = "HTTP",
                    specType = SpecType.OPENAPI.value
                )

                assertThat(ctrfSpecConfig.specification).isEqualTo("openapi/order_api.yaml")
                assertThat(ctrfSpecConfig.sourceProvider).isEqualTo(SourceProvider.git.name)
                assertThat(ctrfSpecConfig.repository).isEqualTo("https://example.com/contracts.git")
            } finally {
                Configuration.configFilePath = originalConfigPath
            }
        }
    }

    @Test
    fun `should reject spec cert override in openapi run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                systemUnderTest:
                  service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                id: api-spec
                                path: api.yaml
                    runOptions:
                      openapi:
                        specs:
                          - spec:
                              id: api-spec
                              baseUrl: https://localhost:9443
                              cert:
                                keyStore:
                                  file: ./client-cert.jks
                                keyStorePassword: password
                """.trimIndent()
            )
        }.hasMessageContaining("cert")
    }

    @Test
    fun `should reject spec mtlsEnabled override in openapi run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: order-api
                                    path: order.yaml
                        runOptions:
                          openapi:
                            specs:
                              - spec:
                                  id: order-api
                                  mtlsEnabled: true
                """.trimIndent()
            )
        }.hasMessageContaining("mtlsEnabled")
    }

    @Test
    fun `should reject spec cert override in wsdl run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: wsdl-spec
                                    path: service.wsdl
                        runOptions:
                          wsdl:
                            specs:
                              - spec:
                                  id: wsdl-spec
                                  baseUrl: https://localhost:9443
                                  cert:
                                    keyStore:
                                      file: ./server-cert.jks
                                    keyStorePassword: password
                """.trimIndent()
            )
        }.hasMessageContaining("cert")
    }

    @Test
    fun `should reject spec mtlsEnabled override in wsdl run options spec`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: wsdl-spec
                                    path: service.wsdl
                        runOptions:
                          wsdl:
                            specs:
                              - spec:
                                  id: wsdl-spec
                                  mtlsEnabled: true
                """.trimIndent()
            )
        }.hasMessageContaining("mtlsEnabled")
    }

    @Test
    fun `should reject incomingMtlsEnabled in v3 config`() {
        assertThatThrownBy {
            v3Config(
                """
                version: 3
                dependencies:
                  services:
                    - service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: order-api
                                    path: order.yaml
                        runOptions:
                          openapi:
                            host: localhost
                            port: 9443
                            cert:
                              incomingMtlsEnabled: true
                              keyStore:
                                file: ./server-cert.jks
                              keyStorePassword: password
                """.trimIndent()
            )
        }.hasMessageContaining("incomingMtlsEnabled")
    }

    @Test
    fun `getStubHooks should return global and file scoped hook associations`() {
        val config = v3Config("""
        version: 3
        dependencies:
          data:
            adapters:
              preSpecmaticRequestProcessor: ./hooks/global_decode.sh
          services:
            - service:
                definitions:
                  - definition:
                      source:
                        filesystem: {}
                      specs:
                        - one.yaml
                        - two.yaml
                data:
                  adapters:
                    postSpecmaticResponseProcessor: ./hooks/service_encode.sh
        """.trimIndent())

        val oneSpecFile = config.getFirstMockSourceMatching { it.specPathInConfig == "one.yaml" }!!.specFile
        val twoSpecFile = config.getFirstMockSourceMatching { it.specPathInConfig == "two.yaml" }!!.specFile
        assertThat(config.getStubHooks()).containsExactlyInAnyOrder(
            FileAssociation.Global(mapOf("preSpecmaticRequestProcessor" to "./hooks/global_decode.sh")),
            FileAssociation.FileScoped(oneSpecFile, mapOf("postSpecmaticResponseProcessor" to "./hooks/service_encode.sh")),
            FileAssociation.FileScoped(twoSpecFile, mapOf("postSpecmaticResponseProcessor" to "./hooks/service_encode.sh")),
        )
    }

    @Nested
    inner class ModificationMethods {
        @Test
        fun `copyResiliencyTestsConfig should create test config when missing`() {
            val updated = v3Config("version: 3").enableResiliencyTests(onlyPositive = false)
            assertThat(updated.getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.all)
        }

        @Test
        fun `enableResiliencyTests should set all resiliency tests`() {
            val updated = v3Config("version: 3").enableResiliencyTests()
            assertThat(updated.getResiliencyTestsEnabled()).isEqualTo(ResiliencyTestSuite.all)
        }

        @Test
        fun `withTestBaseURL should create test config when missing`() {
            val updated = v3Config("version: 3").withTestBaseURL("http://localhost:9090")
            assertThat(updated.getTestBaseUrl(SpecType.OPENAPI)).isEqualTo("http://localhost:9090")
        }

        @Test
        fun `withTestModes should create test settings when missing`() {
            val updated = v3Config("version: 3").withTestModes(strictMode = true, lenientMode = false)
            assertThat(updated.getTestStrictMode()).isTrue()
            assertThat(updated.getTestLenientMode()).isFalse()
        }

        @Test
        fun `withTestModes should preserve existing values when null is passed`() {
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions: []
                settings:
                  strictMode: true
                  lenientMode: false
            """.trimIndent()).withTestModes(strictMode = null, lenientMode = true)
            assertThat(updated.getTestStrictMode()).isTrue()
            assertThat(updated.getTestLenientMode()).isTrue()
        }

        @Test
        fun `withTestFilter should update existing openapi test run options`() {
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - test.yaml
                runOptions:
                  openapi:
                    baseUrl: http://localhost:9000
                    filter: original
            """.trimIndent()).withTestFilter("updated")
            assertThat(updated.getTestFilter()).isEqualTo("updated")
        }

        @Test
        fun `withTestFilter should create and return config when test section is missing`() {
            val original = v3Config("version: 3")
            val updated = original.withTestFilter("updated")
            assertThat(updated.getTestFilter()).isEqualTo("updated")
        }

        @Test
        fun `withTestTimeout should create test settings when missing`() {
            val updated = v3Config("version: 3").withTestTimeout(1234)
            assertThat(updated.getTestTimeoutInMilliseconds()).isEqualTo(1234)
        }

        @Test
        fun `withStubModes should create mock settings when missing`() {
            val updated = v3Config("version: 3").withStubModes(true)
            assertThat(updated.getStubStrictMode(null)).isTrue()
        }

        @Test
        fun `withStubFilter should update existing openapi mock run options`() {
            val updated = v3Config("""
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                path: stub.yaml
                    runOptions:
                      openapi:
                        baseUrl: http://0.0.0.0:9000
                        filter: original
            """.trimIndent()).withStubFilter("updated")
            assertThat(updated.getStubFilter(File("stub.yaml"))).isEqualTo("updated")
        }

        @Test
        fun `withStubFilter should preserve existing filter when null is passed`() {
            val updated = v3Config("""
            version: 3
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: ./specs
                          specs:
                            - spec:
                                path: stub.yaml
                    runOptions:
                      openapi:
                        baseUrl: http://0.0.0.0:9000
                        filter: original
            """.trimIndent()).withStubFilter(null)
            assertThat(updated.getStubFilter(File("stub.yaml"))).isEqualTo("original")
        }

        @Test
        fun `withGlobalMockDelay should create mock settings when missing`() {
            val updated = v3Config("version: 3").withGlobalMockDelay(250)
            assertThat(updated.getStubDelayInMilliseconds(null)).isEqualTo(250)
        }

        @Test
        fun `withMatchBranch should update test and mock sources`() {
            writeSpec("specs/test.yaml")
            writeSpec("specs/stub.yaml")
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        git:
                          url: https://github.com/org/repo
                          branch: main
                          matchBranch: false
                      specs:
                        - test.yaml
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            git:
                              url: https://github.com/org/repo
                              branch: main
                              matchBranch: false
                          specs:
                            - spec:
                                path: stub.yaml
            """.trimIndent()).withMatchBranch(true)

            val entries = updated.getSpecificationSources().flatMap { it.test + it.mock }
            assertThat(entries).isNotEmpty()
            assertThat(entries.map { it.matchBranch }).containsOnly(true)
        }

        @Test
        fun `withResolvedFilesystemDirectories should resolve relative directories against working directory`() {
            writeSpec("specs/test.yaml")
            val workingDir = File("/tmp/specmatic-test-workdir").canonicalFile
            val config = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        filesystem:
                          directory: ./specs
                      specs:
                        - test.yaml
            """.trimIndent())

            val resolved = config.withCanonicalizedDefinitionFilesystemSources(workingDir)
            val entries = resolved.getSpecificationSources().flatMap { it.test }
            assertThat(entries).isNotEmpty()
            assertThat(entries.map { it.directory }).containsOnly(workingDir.resolve("specs").canonicalPath)

            val yaml = resolved.toYaml()
            assertThat(yaml).contains(workingDir.resolve("specs").canonicalPath)
        }

        @Test
        fun `toYaml should serialize and deserialize protobuf runOptions correctly`(@TempDir tempDir: File) {
            val file = tempDir.resolve("test_proto.yaml").apply {
                writeText("""
                version: 3
                components:
                  runOptions:
                    orderAndProductGrpcServiceMock:
                      protobuf:
                        type: mock
                        host: localhost
                        port: 10000
                """.trimIndent())
            }
            val config = file.toSpecmaticConfig()
            val serialized = config.toYaml()
            println("SERIALIZED YAML:\n${serialized}")
            val serializedFile = tempDir.resolve("serialized_proto.yaml").apply { writeText(serialized) }
            val deserialized = serializedFile.toSpecmaticConfig()
            assertThat(deserialized).isNotNull
        }

        @Test
        fun `toYaml should serialize and deserialize wsdl, asyncapi, and graphql runOptions correctly`(@TempDir tempDir: File) {
            val file = tempDir.resolve("test_all.yaml").apply {
                writeText("""
                version: 3
                components:
                  runOptions:
                    myServiceMock:
                      wsdl:
                        type: mock
                        host: localhost
                        port: 8080
                      asyncapi:
                        type: mock
                        inMemoryBroker:
                          host: localhost
                          port: 9092
                      graphqlsdl:
                        type: mock
                        host: localhost
                        port: 4000
                """.trimIndent())
            }
            val config = file.toSpecmaticConfig()
            val serialized = config.toYaml()
            val serializedFile = tempDir.resolve("serialized_all.yaml").apply { writeText(serialized) }
            val deserialized = serializedFile.toSpecmaticConfig()
            assertThat(deserialized).isNotNull
        }

        @Test
        fun `plusExamples should append examples to test and mock sources`() {
            writeSpec("specs/test.yaml")
            writeSpec("specs/stub.yaml")
            val updated = v3Config("""
            version: 3
            systemUnderTest:
              service:
                definitions:
                  - definition:
                      source:
                        git:
                          url: https://github.com/org/repo
                          branch: main
                          matchBranch: false
                      specs:
                        - test.yaml
            dependencies:
              services:
                - service:
                    definitions:
                      - definition:
                          source:
                            git:
                              url: https://github.com/org/repo
                              branch: main
                              matchBranch: false
                          specs:
                            - spec:
                                path: stub.yaml
            """.trimIndent()).plusExamples(listOf("examples"))

            val entries = updated.getSpecificationSources().flatMap { it.test + it.mock }
            assertThat(entries).isNotEmpty()
            assertThat(entries.map { it.exampleDirs }).allSatisfy {
                assertThat(it).containsExactly("examples")
            }
        }

        @Test
        fun `should fail to load v3 config when service definitions are missing`() {
            assertThatThrownBy {
                v3Config("""
                version: 3
                systemUnderTest:
                  service:
                    runOptions:
                      openapi:
                        baseUrl: http://localhost:9000
                """.trimIndent())
            }.hasMessageContaining("definitions")
        }

        private fun v3Config(yaml: String): SpecmaticConfig {
            return tempDir.resolve("config-${System.nanoTime()}.yaml").apply { writeText(yaml) }.toSpecmaticConfig()
        }

        private fun writeSpec(path: String): File {
            return tempDir.resolve(path).apply {
                parentFile.mkdirs()
                writeText("openapi: 3.0.0\ninfo: { title: test, version: '1.0.0' }\npaths: {}\n")
            }
        }
    }

    @Test
    fun `getStubDictionary should fallback to dependency level dictionary when service dictionary is absent for matched spec`() {
        val config = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 3
            dependencies:
              services:
              - service:
                  definitions:
                  - definition:
                      source:
                        filesystem: {}
                      specs:
                        - simple.yaml
              data:
                dictionary:
                  path: ./dictionary_global.yaml
            """.trimIndent())
        }.toSpecmaticConfig()

        val specificationSourceEntry = config.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" }
        assertThat(specificationSourceEntry).isNotNull
        assertThat(config.getStubDictionary(specificationSourceEntry!!.specFile)).isEqualTo("./dictionary_global.yaml")
    }

    companion object {
        data class TestCase(val v2: String, val v3: String, val extract: (SpecmaticConfig) -> Any?) {
            fun run(tempDir: File) {
                val v2Config = tempDir.resolve("v2.yaml").apply { writeText(v2) }.toSpecmaticConfig()
                val v3Config = tempDir.resolve("v2.yaml").apply { writeText(v3) }.toSpecmaticConfig()
                val v2Value = extract(v2Config)
                val v3Value = extract(v3Config)

                // ALl except GitMonoRepo returns .path instead of .canonicalPath
                assertThat(v2Value)
                    .usingRecursiveComparison()
                    .ignoringFieldsMatchingRegexes(".*specmaticConfig")
                    .isEqualTo(v3Value)
            }

            fun skip(): TestCase? = null
        }

        @JvmStatic
        fun loadSourcesTestCases(): List<TestCase> {
            return listOfNotNull(
                // Common git and fileSystem cases
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      provides:
                        - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - simple.yaml
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - git:
                        url: https://github.com/org/repo
                        branch: feature
                        matchBranch: true
                      provides:
                        - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                git:
                                  url: https://github.com/org/repo
                                  branch: feature
                                  matchBranch: true
                              specs:
                                - simple.yaml
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),

                // baseUrl Cases
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              baseUrl: http://0.0.0.0:3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: MyCustomId
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              baseUrl: http://localhost:9000
                              specs:
                              - spec:
                                  id: MyCustomId
                                  baseUrl: http://0.0.0.0:3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: MyCustomId
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              host: 0.0.0.0
                              port: 3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      consumes:
                        - port: 3000
                          basePath: /v1
                          specs:
                            - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - spec:
                                    id: MyCustomId
                                    path: simple.yaml
                                    urlPathPrefix: /v1
                          runOptions:
                            openapi:
                              host: localhost
                              port: 9000
                              specs:
                              - spec:
                                  id: MyCustomId
                                  host: 0.0.0.0
                                  port: 3000
                    """.trimIndent(),
                    extract = { it.loadSources() },
                ),
            )
        }

        @JvmStatic
        fun externalSpecTypeConfigs(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      provides:
                        - specType: protobuf
                          config:
                            host: localhost
                            port: 5000
                          specs:
                          - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - simple.yaml
                        runOptions:
                          protobuf:
                            host: localhost
                            port: 5000
                    """.trimIndent(),
                    extract = { it.testConfigFor("simple.yaml", SpecType.PROTOBUF.value)?.resolvedConfig() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    contracts:
                    - filesystem:
                        directory: ./specs
                      provides:
                        - specType: protobuf
                          config:
                            host: localhost
                            port: 5000
                          specs:
                          - simple.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions:
                          - definition:
                              source:
                                filesystem:
                                  directory: ./specs
                              specs:
                                - simple.yaml
                        runOptions:
                          protobuf:
                            host: localhost
                            port: 5000
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstTestSourceMatching { source -> source.specPathInConfig == "simple.yaml" } ?: return@TestCase null
                        it.testConfigFor(source.specFile, SpecType.PROTOBUF)?.resolvedConfig()
                    },
                ),
            )
        }

        @JvmStatic
        fun testSettings(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    test:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        test:
                          strictMode: true
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    test:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions: []
                        settings:
                          strictMode: true
                    specmatic:
                      settings:
                        test:
                          strictMode: false
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
            )
        }

        @JvmStatic
        fun stubSettings(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        mock:
                          strictMode: true
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      strictMode: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services: []
                      settings:
                        strictMode: true
                    specmatic:
                      settings:
                        mock:
                          strictMode: false
                    """.trimIndent(),
                    extract = { it.getTestStrictMode() },
                ),
            )
        }

        @JvmStatic
        fun generalSettings(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    fuzzy: true
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        general:
                          featureFlags:
                            fuzzyMatcherForPayloads: true
                    """.trimIndent(),
                    extract = { it.getFuzzyMatchingEnabled() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    prettyPrint: false
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    specmatic:
                      settings:
                        general:
                          prettyPrint: false
                    """.trimIndent(),
                    extract = { it.getPrettyPrint() },
                ),
            )
        }

        @JvmStatic
        fun stubData(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    hooks:
                      preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services: []
                      data:
                        adapters:
                          preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    extract = { it.getFuzzyMatchingEnabled() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    hooks:
                      preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services: []
                      data:
                        adapters:
                          ${"$"}ref: "#/components/adapters/myAdapters"
                    components:
                      adapters:
                        myAdapters:
                          preSpecmaticRequestProcessor: ./hooks/decode_request_from_consumer.sh
                    """.trimIndent(),
                    extract = { it.getFuzzyMatchingEnabled() },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      dictionary: ./dictionary.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            dictionary:
                              path: ./dictionary_specific.yaml
                      data:
                        dictionary:
                          path: ./dictionary.yaml
                    """.trimIndent(),
                    extract = { it.getStubDictionary(null) },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      dictionary: ./dictionary.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            dictionary:
                              path: ./dictionary.yaml
                      data:
                        dictionary:
                          path: ./dictionary_global.yaml
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getStubDictionary(null)
                        it.getStubDictionary(source.specFile)
                    },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    examples:
                    - ./mock-examples
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                      data:
                        examples:
                        - directories:
                          - ./mock-examples
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getExamples()
                        it.getStubExampleDirs(source.specFile)
                    },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    examples:
                    - ./mock-examples
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            examples:
                            - directories:
                              - ./mock-examples
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getExamples()
                        it.getStubExampleDirs(source.specFile)
                    },
                ),
                TestCase(
                    v2 = """
                    version: 2
                    examples:
                    - ./mock-examples
                    - ./common-examples
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    dependencies:
                      services:
                      - service:
                          definitions:
                          - definition:
                              source:
                                filesystem: {}
                              specs:
                                - simple.yaml
                          data:
                            examples:
                            - directories:
                              - ./mock-examples
                      data:
                        examples:
                        - directories:
                          - ./common-examples
                    """.trimIndent(),
                    extract = {
                        val source = it.getFirstMockSourceMatching { it.specPathInConfig == "simple.yaml" } ?: return@TestCase it.getExamples()
                        it.getStubExampleDirs(source.specFile)
                    },
                ),
            )
        }

        @JvmStatic
        fun testData(): List<TestCase> {
            return listOfNotNull(
                TestCase(
                    v2 = """
                    version: 2
                    stub:
                      dictionary: ./dictionary.yaml
                    """.trimIndent(),
                    v3 = """
                    version: 3
                    systemUnderTest:
                      service:
                        definitions: []
                        data:
                          dictionary:
                            path: ./dictionary.yaml
                    """.trimIndent(),
                    extract = { it.getTestDictionary() },
                ),
            )
        }
    }

    private fun v3Config(yaml: String): SpecmaticConfig {
        return tempDir.resolve("config-${System.nanoTime()}.yaml").apply { writeText(yaml) }.toSpecmaticConfig()
    }

    private fun emptyKeyStore(): KeyStore {
        return KeyStore.getInstance("JKS").apply { load(null, null) }
    }

    private fun testServiceConfig(source: SourceV3, configuredSpecPath: String): TestServiceConfig =
        TestServiceConfig(
            service = RefOrValue.Value(
                CommonServiceConfig<TestRunOptions, TestSettings>(
                    definitions = listOf(
                        Definition(
                            Definition.Value(
                                source = RefOrValue.Value(source),
                                specs = listOf(SpecificationDefinition.StringValue(configuredSpecPath))
                            )
                        )
                    )
                )
            )
        )

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + args).start()
        val exitCode = process.waitFor()
        assertThat(exitCode).isZero()
    }

    private fun minimalOpenApi(): String = "openapi: 3.0.0\ninfo:\n  title: Orders\n  version: '1'\npaths: {}\n"
}
