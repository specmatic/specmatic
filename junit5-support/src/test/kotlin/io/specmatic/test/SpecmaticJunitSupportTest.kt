package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.ResiliencyTestSuite
import io.specmatic.core.Scenario
import io.specmatic.core.SPECMATIC_STUB_DICTIONARY
import io.specmatic.core.SpecmaticConfigV1V2Common
import io.specmatic.core.TestConfig
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v2.ContractConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.core.utilities.yamlMapper
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.utilities.Decision
import io.specmatic.core.utilities.Flags
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PROTOCOL
import io.specmatic.test.SpecmaticJUnitSupport.Companion.TEST_BASE_URL
import io.specmatic.test.listeners.ContractExecutionListener
import io.specmatic.test.reports.TestReportListener
import io.specmatic.test.reports.coverage.Endpoint
import io.specmatic.test.reports.TestExecutionResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.junit.platform.launcher.TestExecutionListener
import org.opentest4j.TestAbortedException
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.io.PrintStream
import java.util.*

class SpecmaticJunitSupportTest {
    companion object {
        val initialPropertyKeys = System.getProperties().mapKeys { it.key.toString() }.keys

        @JvmStatic
        fun missingSpecConfigTemplates(): List<String> =
            listOf(
                """
                version: 3
                systemUnderTest:
                  service:
                    definitions:
                      - definition:
                          source:
                            filesystem:
                              directory: %s
                          specs:
                            - missing.yaml
                """.trimIndent(),
                """
                version: 2
                contracts:
                  - filesystem:
                      directory: %s
                    provides:
                      - missing.yaml
                """.trimIndent()
            )

        @JvmStatic
        fun gitSpecCases(): List<Arguments> =
            listOf(
                Arguments.of("missing.yaml", false, true),
                Arguments.of("existing.yaml", true, false)
            )
    }

    @Test
    fun `should retain open api path parameter convention for parameterized endpoints`() {
        val result = SpecmaticJUnitSupport().loadTestScenarios(
            "./src/test/resources/spec_with_parameterized_paths.yaml",
            "",
            "",
            TestConfig(emptyMap(), emptyMap()),
            filterName = null,
            filterNotName = null,
            filter = ScenarioMetadataFilter.from("")
        )
        val specEndpoints = result.allEndpoints
        assertThat(specEndpoints.count()).isEqualTo(2)
        assertThat(specEndpoints.all { it.path == "/sayHello/{name}" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://test.com", "https://my-json-server.typicode.com/specmatic/specmatic-documentation"])
    fun `should pick up and use valid testBaseURL system property when set`(validURL: String) {
        System.setProperty(TEST_BASE_URL, validURL)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo(validURL)
    }

    @Test
    fun `should pick up host and port when testBaseURL system property is not set`() {
        val domain = "test.com"
        val port = "8080"
        System.setProperty(HOST, domain)
        System.setProperty(PORT, port)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo("http://$domain:$port")
    }

    @Test
    fun `should take the domain from the host system property when it is an URI`() {
        val domainName = "test.com"
        val domain = "http://$domainName"
        val port = "8080"
        System.setProperty(HOST, domain)
        System.setProperty(PORT, port)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo("http://$domainName:$port")
    }

    @Test
    fun `should pick use protocol when system property is set`() {
        val protocol = "https"
        val domain = "test.com"
        val port = "8080"
        System.setProperty(HOST, domain)
        System.setProperty(PORT, port)
        System.setProperty(PROTOCOL, protocol)
        lateinit var url: String
        assertThatCode {
            url = SpecmaticJUnitSupport().constructTestBaseURL()
        }.doesNotThrowAnyException()
        assertThat(url).isEqualTo("$protocol://$domain:$port")
    }

    @Test
    fun `should prefer explicit staged testBaseURL over config base url`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://config.example:9000")
        SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(configFile = configFile.absolutePath, testBaseURL = "http://override.example:8080"))
        try {
            val url = SpecmaticJUnitSupport().constructTestBaseURL()
            assertThat(url).isEqualTo("http://override.example:8080")
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `should prefer staged host and port over config base url`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://config.example:9000")
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                configFile = configFile.absolutePath,
                otherArguments = DeprecatedArguments(host = "override.example", port = "8081", isHostOrPortExplicitlySpecified = true)
            )
        )

        try {
            val url = SpecmaticJUnitSupport().constructTestBaseURL()
            assertThat(url).isEqualTo("http://override.example:8081")
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `should prefer config base url when staged host and port are not explicitly specified`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://config.example:9000")
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                configFile = configFile.absolutePath,
                otherArguments = DeprecatedArguments(host = "localhost", port = "9000", isHostOrPortExplicitlySpecified = false)
            )
        )

        try {
            val url = SpecmaticJUnitSupport().constructTestBaseURL()
            assertThat(url).isEqualTo("http://config.example:9000")
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `should use config base url when no staged base url or host port are provided`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://config.example:9000")
        SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(configFile = configFile.absolutePath))
        try {
            val url = SpecmaticJUnitSupport().constructTestBaseURL()
            assertThat(url).isEqualTo("http://config.example:9000")
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `should validate config base url when used as fallback`(@TempDir tempDir: File) {
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = "http://invalid url.com")
        SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(configFile = configFile.absolutePath))
        try {
            val ex = assertThrows<TestAbortedException> { SpecmaticJUnitSupport().constructTestBaseURL() }
            assertThat(ex.message).isEqualTo("Please specify a valid URL in 'scheme://host[:port][path]' format in config file")
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://invalid url.com", "http://localhost:abcd/", "http://localhost:80 80/", "http://localhost:a123/test"])
    fun `testBaseURL system property should be valid URI`(invalidURL: String) {
        System.setProperty(TEST_BASE_URL, invalidURL)
        val ex = assertThrows<TestAbortedException> {
                SpecmaticJUnitSupport().constructTestBaseURL()
            }
        assertThat(ex.message).isEqualTo("Please specify a valid URL in 'scheme://host[:port][path]' format in testBaseURL environment variable")
    }

    @Test
    fun `host system property should be valid`() {
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                otherArguments = DeprecatedArguments(
                    host = "invalid domain",
                    port = "8080",
                    protocol = "https",
                    isHostOrPortExplicitlySpecified = true
                )
            )
        )
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
    }

    @Test
    fun `protocol system property should be valid`() {
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                otherArguments = DeprecatedArguments(
                    host = "test.com",
                    port = "8080",
                    protocol = "invalid",
                    isHostOrPortExplicitlySpecified = true
                )
            )
        )
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a valid $PROTOCOL, $HOST and $PORT environment variables")
    }

    @Test
    fun `port system property should be valid`() {
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                otherArguments = DeprecatedArguments(
                    host = "test.com",
                    port = "invalid_port",
                    protocol = "https",
                    isHostOrPortExplicitlySpecified = true
                )
            )
        )
        val ex = assertThrows<TestAbortedException> {
            SpecmaticJUnitSupport().constructTestBaseURL()
        }
        assertThat(ex.message).isEqualTo("Please specify a number value for $PORT environment variable")
    }

    @Test
    fun `defaults to http localhost 9000 when testBaseURL not set`() {
        val url = SpecmaticJUnitSupport().constructTestBaseURL()
        assertThat(url).isEqualTo("http://localhost:9000")
    }

    @Test
    fun `ContractExecutionListener should be registered`() {
        val registeredListeners = ServiceLoader.load(TestExecutionListener::class.java)
            .map { it.javaClass.name }
            .toMutableList()

        assertThat(registeredListeners).contains(ContractExecutionListener::class.java.name)
    }

    @Test
    fun `should be able to get actuator endpoints from swaggerUI`() {
        val contractTestHarness = SpecmaticJUnitSupport()

        contractTestHarness.actuatorFromSwagger("", object: TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                return HttpResponse(
                    200,
                    body = """
                    openapi: 3.0.1
                    info:
                      title: Order BFF
                      version: '1.0'
                    paths:
                      /orders:
                        post:
                          responses:
                            '200':
                              description: OK
                      /products:
                        post:
                          responses:
                            '200':
                              description: OK
                      /findAvailableProducts/{date_time}:
                        get:
                          parameters:
                            - ${"$"}ref: '#/components/parameters/DateTimeParameter'
                          responses:
                            '200':
                              description: OK
                    components:
                        schemas:
                            DateTime:
                                type: string
                                format: date-time
                        parameters:
                            DateTimeParameter:
                                name: date_time
                                in: path
                                required: true
                                schema:
                                    ${"$"}ref: '#/components/schemas/DateTime'
                    """.trimIndent()
                )
            }
        })

        assertThat(contractTestHarness.openApiCoverageReportInput.endpointsAPISet).isTrue()
        assertThat(contractTestHarness.openApiCoverageReportInput.getApplicationAPIs()).isEqualTo(listOf(
            API("POST", "/orders"),
            API("POST", "/products"),
            API("GET", "/findAvailableProducts/{date_time}")
        ))
    }

    @Test
    fun `strict mode only runs tests for APIs with external examples`(@TempDir tempDir: File) {
        // Create OpenAPI spec with 2 endpoints
        val openApiSpec = """
openapi: 3.0.0
info:
  title: Test API
  version: 1.0.0
paths:
  /users/{id}:
    get:
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: Success
          content:
            application/json:
              schema:
                type: object
                required:
                  - id
                  - name
                properties:
                  id:
                    type: integer
                  name:
                    type: string
  /users:
    post:
      requestBody:
        content:
          application/json:
            schema:
              type: object
              required:
                - name
              properties:
                name:
                  type: string
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema:
                type: object
                required:
                  - id
                properties:
                  id:
                    type: integer
        """.trimIndent()

        try {
            val specFile = tempDir.resolve("api.yaml")
            specFile.writeText(openApiSpec)

            val examplesDir = tempDir.resolve("api_examples")
            examplesDir.mkdirs()

            // Create example only for GET /users/{id}
            val exampleFile = examplesDir.resolve("get_user_example.json")
            exampleFile.writeText("""
{
  "http-request": {
    "method": "GET",
    "path": "/users/1"
  },
  "http-response": {
    "status": 200,
    "body": {
      "id": 1,
      "name": "John Doe"
    }
  }
}
            """.trimIndent())

            // Test with strictMode = true
            SpecmaticJUnitSupport.settingsStaging.set(
                ContractTestSettings(
                    testBaseURL = "http://localhost:8080",
                    contractPaths = specFile.absolutePath,
                    filter = "",
                    configFile = "",
                    generative = false,
                    reportBaseDirectory = null,
                    coverageHooks = emptyList(),
                    strictMode = true
                )
            )

            val specmaticJunitSupportWithStrictMode = SpecmaticJUnitSupport()
            val (testsWithStrictMode, _) = specmaticJunitSupportWithStrictMode.loadTestScenarios(
                specFile.absolutePath,
                "",
                "",
                TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                filter = ScenarioMetadataFilter.from("")
            )

            // Verify the test name contains the correct endpoint and size
            val testsWithStrictModeList = testsWithStrictMode.toList()
            val testDescriptionsWithStrictMode = testsWithStrictModeList.executedTestDescriptions()
            assertThat(testDescriptionsWithStrictMode.size).isEqualTo(1)
            assertThat(testDescriptionsWithStrictMode).allMatch {
                it.contains("GET /users/(id:number) -> 200")
            }
            assertThat(testDescriptionsWithStrictMode).noneMatch {
                it.contains("POST")
            }

            // Test with strictMode = false
            SpecmaticJUnitSupport.settingsStaging.set(
                ContractTestSettings(
                    testBaseURL = "http://localhost:8080",
                    contractPaths = specFile.absolutePath,
                    filter = "",
                    configFile = "",
                    generative = false,
                    reportBaseDirectory = null,
                    coverageHooks = emptyList(),
                    strictMode = false
                )
            )

            val specmaticJunitSupportWithoutStrictMode = SpecmaticJUnitSupport()
            val (testsWithoutStrictMode, _) = specmaticJunitSupportWithoutStrictMode.loadTestScenarios(
                specFile.absolutePath,
                "",
                "",
                TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                filter = ScenarioMetadataFilter.from("")
            )

            // Verify test names include both endpoints and size
            val testsWithoutStrictModeList = testsWithoutStrictMode.toList()
            val testDescriptionsWithoutStrictMode = testsWithoutStrictModeList.executedTestDescriptions()
            assertThat(testDescriptionsWithoutStrictMode.size).isGreaterThan(1)
            assertThat(testDescriptionsWithoutStrictMode).anyMatch {
                it.contains("GET /users/(id:number) -> 200")
            }
            assertThat(testDescriptionsWithoutStrictMode).anyMatch {
                it.contains("POST /users -> 201")
            }
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `filtered endpoints should not appear in the return value of loadTestScenarios`() {
        val specFile = File("src/test/resources/filter_test/product_search_bff_v4.yaml")
        val testData = SpecmaticJUnitSupport().loadTestScenarios(
            path = specFile.canonicalPath,
            suggestionsPath = "", suggestionsData = "",
            config = TestConfig(emptyMap(), emptyMap()),
            filterName = "", filterNotName = "",
            filter = ScenarioMetadataFilter.from("!(PATH='/products' && METHOD='POST' && STATUS='201')")
        )

        assertThat(testData.scenarios.executedTestDescriptions()).doesNotContain(" Scenario: POST /products -> 201 with the request from the example 'SUCCESS'")
        assertThat(testData.allEndpoints).contains(
            Endpoint(
                path = "/products",
                method = "POST",
                responseStatus = 201,
                requestContentType = "application/json",
                responseContentType = "application/json",
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
        assertThat(testData.filteredEndpoints).doesNotContain(
            Endpoint(
                path = "/products",
                method = "POST",
                responseStatus = 201,
                requestContentType = "application/json",
                responseContentType = "application/json",
                protocol = SpecmaticProtocol.HTTP, specType = SpecType.OPENAPI
            )
        )
    }

    @Test
    fun `contractTest should mark scenarios as EXCLUDED when filter excludes them`(@TempDir tempDir: File) {
        val specFile = File("src/test/resources/openapi/alpha_beta_spec.yaml")
        val (server, baseUrl) = startAlphaBetaStubServer()
        try {
            SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(testBaseURL = baseUrl, contractPaths = specFile.canonicalPath, configFile = "", generative = false, filter = "PATH='/alpha'",))
            val output = captureStdout {
                val tests = SpecmaticJUnitSupport().contractTest().toList()
                assertThat(tests).hasSize(1)
            }

            assertThat(output).containsSubsequence(
                "Skipping Scenario: GET /beta -> 200 (responseContentType application/json)", "Excluded from Run",
                "This operation was skipped because it did not match the selected filters"
            )
        } finally {
            server.stop(0)
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `contractTest should use expression filter in no tests found message and mark scenarios as EXCLUDED`(@TempDir tempDir: File) {
        val specFile = File("src/test/resources/openapi/alpha_beta_spec.yaml")
        val (server, baseUrl) = startAlphaBetaStubServer()
        try {
            SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(testBaseURL = baseUrl, contractPaths = specFile.canonicalPath, configFile = "", generative = false, filter = "METHOD='PATCH'"))
            val tests = SpecmaticJUnitSupport().contractTest().toList()
            val error = assertThrows<AssertionError> { tests.single().executable.execute() }
            assertThat(error.message).contains("No tests found to run.")
            assertThat(error.message).contains("expression filter: \"METHOD='PATCH'\"")
        } finally {
            server.stop(0)
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `contractTest should skip scenarios beyond maxTestCount`(@TempDir tempDir: File) {
        val specFile = File("src/test/resources/openapi/alpha_beta_spec.yaml")
        val configFile = writeSpecmaticConfig(tempDir, baseUrl = null, maxTestCount = 1)
        val (server, baseUrl) = startAlphaBetaStubServer()
        try {
            SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(testBaseURL = baseUrl, contractPaths = specFile.canonicalPath, configFile = configFile.canonicalPath, generative = false, filter = ""))
            val output = captureStdout {
                val tests = SpecmaticJUnitSupport().contractTest()
                assertDoesNotThrow { tests.forEach { it.executable.execute() } }
            }

            assertThat(output).containsSubsequence(
                "Skipping Scenario: GET /beta -> 200 (responseContentType application/json) with the request from the example 'ok'",
                "Maximum Test Count Exceeded",
                "This operation was skipped because it exceeded the maximum test count"
            )
        } finally {
            server.stop(0)
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `should load only example of scenarios which have been filtered`() {
        val specFile = File("src/test/resources/invalid_example/openapi.yaml")
        assertDoesNotThrow {
            SpecmaticJUnitSupport().loadTestScenarios(
                path = specFile.canonicalPath, suggestionsPath = "", suggestionsData = "",
                config = TestConfig(emptyMap(), emptyMap()), filterName = null, filterNotName = null,
                filter = ScenarioMetadataFilter.from("PATH='/test' && METHOD='post'")
            )
        }
    }

    @Test
    fun `loadTestScenarios should pass filter to external example loading`() {
        val specFile = File("src/test/resources/openapi/has_irrelevant_externalized_test.yaml")
        val strictModeConfig = SpecmaticConfigV1V2Common().withTestModes(strictMode = true, lenientMode = null)
        val loaded = assertDoesNotThrow {
            SpecmaticJUnitSupport().loadTestScenarios(
                path = specFile.canonicalPath,
                suggestionsPath = "",
                suggestionsData = "",
                config = TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                specmaticConfig = strictModeConfig,
                filter = ScenarioMetadataFilter.from("METHOD='GET'")
            )
        }

        assertThat(loaded.scenarios.executedTestDescriptions()).allMatch { it.contains("GET /order_action_figure") }
    }

    @Test
    fun `loadTestScenarios should load externalized examples before filtering scenarios by tags`() {
        val specFile = File("src/test/resources/openapi/filter_by_tags_externalized_examples.yaml")
        val strictModeConfig = SpecmaticConfigV1V2Common().withTestModes(strictMode = true, lenientMode = null)
        val loaded = assertDoesNotThrow {
            SpecmaticJUnitSupport().loadTestScenarios(
                path = specFile.canonicalPath,
                suggestionsPath = "",
                suggestionsData = "",
                config = TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                specmaticConfig = strictModeConfig,
                filter = ScenarioMetadataFilter.from("TAGS='WIP'")
            )
        }

        assertThat(loaded.scenarios.executedTestDescriptions())
            .anyMatch { it.contains("GET /orders -> 200") }
    }

    @Test
    fun `loadTestScenarios should generate 4xx negative scenarios from externalized 2xx examples`() {
        val specFile = File("src/test/resources/openapi/filter_by_tags_externalized_examples.yaml")
        val strictModeConfig = SpecmaticConfigV1V2Common().withTestModes(strictMode = true, lenientMode = null)
        val loaded = assertDoesNotThrow {
            SpecmaticJUnitSupport().loadTestScenarios(
                path = specFile.canonicalPath,
                suggestionsPath = "",
                suggestionsData = "",
                config = TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                specmaticConfig = strictModeConfig,
                generative = ResiliencyTestSuite.all,
                filter = ScenarioMetadataFilter.from("")
            )
        }

        val executedTestDescriptions = loaded.scenarios.executedTestDescriptions()
        assertThat(executedTestDescriptions).anyMatch {
            it.contains("-ve") &&
                it.contains("GET /orders -> 4xx") &&
                it.contains("with the request from the example 'INLINE_GET_ORDERS'")
        }

        assertThat(executedTestDescriptions).anyMatch {
            it.contains("-ve") &&
                it.contains("GET /orders -> 4xx") &&
                it.contains("with the request from the example 'Get Orders'")
        }
    }

    @Test
    fun `loadTestScenarios should filter invalid examples in lenient mode and return validation result`() {
        val specFile = File("src/test/resources/invalid_example/openapi.yaml")
        val specmaticConfig = SpecmaticConfigV1V2Common().withTestModes(strictMode = null, lenientMode = true)
        val loaded = SpecmaticJUnitSupport().loadTestScenarios(
            path = specFile.canonicalPath,
            suggestionsPath = "",
            suggestionsData = "",
            config = TestConfig(emptyMap(), emptyMap()),
            filterName = null,
            filterNotName = null,
            specmaticConfig = specmaticConfig,
            filter = ScenarioMetadataFilter.from("")
        )

        val testDescriptions = loaded.scenarios.executedTestDescriptions()
        assertThat(loaded.exampleValidationResult).isInstanceOf(Result.Failure::class.java)
        assertThat(loaded.exampleValidationResult.reportString()).contains("invalid_test_GET_200.json").contains("Error loading example")
        assertThat(testDescriptions).anyMatch { it.contains("POST /test -> 201") }
        assertThat(testDescriptions).noneMatch { it.contains("invalid_test_GET_200") }
        assertThat(testDescriptions).anyMatch { it.contains("GET /test -> 200") }
    }

    @Test
    fun `loadTestScenarios should throw when invalid examples exist in non lenient mode`() {
        val specFile = File("src/test/resources/invalid_example/openapi.yaml")
        val specmaticConfig = SpecmaticConfigV1V2Common().withTestModes(strictMode = null, lenientMode = false)
        val exception = assertThrows<ContractException> {
            SpecmaticJUnitSupport().loadTestScenarios(
                path = specFile.canonicalPath,
                suggestionsPath = "",
                suggestionsData = "",
                config = TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                specmaticConfig = specmaticConfig,
                filter = ScenarioMetadataFilter.from("")
            )
        }

        assertThat(exception.report()).contains("invalid_test_GET_200.json")
    }

    @Test
    fun `contractTest should send example validation errors to coverage hooks`() {
        val specFile = File("src/test/resources/invalid_example/openapi.yaml")
        val listener = RecordingExampleErrorsListener()
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                testBaseURL = "http://localhost:1",
                contractPaths = specFile.canonicalPath,
                filter = "",
                configFile = "",
                generative = false,
                reportBaseDirectory = null,
                coverageHooks = listOf<TestReportListener>(listener),
                strictMode = false,
                lenientMode = true
            )
        )

        try {
            SpecmaticJUnitSupport().contractTest().toList()
            assertThat(listener.exampleErrorsCalls).hasSize(1)
            val resultsBySpec = listener.exampleErrorsCalls.single()
            assertThat(resultsBySpec).containsKey(specFile.canonicalPath)
            val result = resultsBySpec.getValue(specFile.canonicalPath)
            assertThat(result).isInstanceOf(Result.Failure::class.java)
            assertThat(result.reportString()).contains("invalid_test_GET_200.json").contains("Error loading example")
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `contractTest should send test decisions to coverage hooks via OpenApiCoverageReportInput`() {
        val listener = RecordingTestDecisionListener()
        val specFile = File("src/test/resources/openapi/alpha_beta_spec.yaml")
        val (server, baseUrl) = startAlphaBetaStubServer()

        try {
            SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(testBaseURL = baseUrl, contractPaths = specFile.canonicalPath, coverageHooks = listOf(listener)))
            SpecmaticJUnitSupport().contractTest().toList()
            assertThat(listener.decisions).isNotEmpty.allSatisfy { assertThat(it).isInstanceOf(Decision.Execute::class.java) }
        } finally {
            server.stop(0)
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @Test
    fun `should load soapAction from scenarios into Endpoints if specification is WSDL`() {
        val specFile = File("src/test/resources/simple.wsdl")
        val specmaticJUnitSupport = SpecmaticJUnitSupport()
        val loadedScenarios = specmaticJUnitSupport.loadTestScenarios(
            path = specFile.canonicalPath, suggestionsPath = "", suggestionsData = "",
            config = TestConfig(emptyMap(), emptyMap()), filterName = null, filterNotName = null,
            filter = ScenarioMetadataFilter.from("")
        )

        // asserting SpecType as OPENAPI because of the comment at io.specmatic.core.FeatureKt.getSpecType
        assertThat(loadedScenarios.allEndpoints).containsExactlyInAnyOrder(
            Endpoint(
                path = "/ws",
                method = "POST",
                responseStatus = 200,
                soapAction = "getInventory",
                specification = specFile.canonicalPath,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            ),
            Endpoint(
                path = "/ws",
                method = "POST",
                responseStatus = 200,
                soapAction = "addInventory",
                specification = specFile.canonicalPath,
                protocol = SpecmaticProtocol.HTTP,
                specType = SpecType.OPENAPI
            )
        )
    }

    @Test
    fun `should merge previous test runs into the generated coverage report`() {
        val previousRecord = TestResultRecord(
            path = "/previous",
            method = "POST",
            responseStatus = 201,
            request = null,
            response = null,
            result = TestResult.Success,
            specType = SpecType.OPENAPI
        )

        SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(previousTestRuns = listOf(previousRecord)))
        try {
            val support = SpecmaticJUnitSupport()
            val currentRecord = TestResultRecord(
                path = "/current",
                method = "GET",
                responseStatus = 200,
                request = null,
                response = null,
                result = TestResult.Success,
                specType = SpecType.OPENAPI
            )

            support.openApiCoverageReportInput.addTestReportRecords(currentRecord)
            val report = support.openApiCoverageReportInput.generate()

            assertThat(report.testResultRecords).contains(previousRecord, currentRecord)
            assertThat(report.coverageRows).anyMatch { it.path == "/previous" }
            assertThat(report.coverageRows).anyMatch { it.path == "/current" }
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    @ParameterizedTest
    @MethodSource("missingSpecConfigTemplates")
    fun `should warn when a config-driven filesystem specification path does not exist`(
        configTemplate: String,
        @TempDir tempDir: File
    ) {
        val configFile = tempDir.resolve("specmatic.yaml")
        configFile.writeText(configTemplate.format(tempDir.canonicalPath))

        val output = runContractTestWithConfig(configFile.canonicalPath)

        assertThat(output).contains("WARNING").contains("missing.yaml")
    }

    @Test
    fun `should report load error when explicit contract path does not exist`() {
        val missingSpecPath = File("missing-contract.yaml").absolutePath

        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                contractPaths = missingSpecPath
            )
        )

        val tests = SpecmaticJUnitSupport().contractTest().toList()

        assertThat(tests).hasSize(1)
        assertThat(tests.single().displayName).isEqualTo("Specmatic Test Suite")
    }

    @ParameterizedTest
    @MethodSource("gitSpecCases")
    fun `should handle missing-spec warning for git source based on spec existence`(
        specificationPath: String,
        createSpecFile: Boolean,
        expectWarning: Boolean,
        @TempDir tempDir: File
    ) {
        val gitRepoName = "contracts-${UUID.randomUUID()}"
        val gitRepoDir = tempDir.resolve(gitRepoName)
        val originalUserDir = System.getProperty("user.dir")
        val projectWorkingDirRepoDir = originalUserDir?.let { File(it).resolve(".specmatic/repos/$gitRepoName") }

        try {
            gitRepoDir.mkdirs()
            runCommand(gitRepoDir, "git", "init")
            runCommand(gitRepoDir, "git", "config", "user.email", "specmatic@test.local")
            runCommand(gitRepoDir, "git", "config", "user.name", "Specmatic Test")
            if (createSpecFile) {
                gitRepoDir.resolve(specificationPath).writeText(
                    """
                    openapi: 3.0.0
                    info:
                      title: Sample
                      version: 1.0.0
                    paths:
                      /health:
                        get:
                          responses:
                            '200':
                              description: OK
                    """.trimIndent()
                )
            } else {
                gitRepoDir.resolve("README.md").writeText("contracts")
            }
            runCommand(gitRepoDir, "git", "add", ".")
            runCommand(gitRepoDir, "git", "commit", "-m", "initial commit")
            System.setProperty("user.dir", tempDir.canonicalPath)

            val configFile = tempDir.resolve("specmatic.yaml")
            configFile.writeText(
                """
                version: 3
                systemUnderTest:
                  service:
                    definitions:
                      - definition:
                          source:
                            git:
                              url: ${gitRepoDir.canonicalPath}
                          specs:
                            - $specificationPath
                """.trimIndent()
            )

            val output = runContractTestWithConfig(configFile.canonicalPath)
            if (expectWarning) {
                assertThat(output).contains("WARNING: Specification '$specificationPath'").contains("could not be found at")
            } else {
                assertThat(output).doesNotContain("WARNING: Specification '$specificationPath'")
            }
        } finally {
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir)
            }
            projectWorkingDirRepoDir?.deleteRecursively()
        }
    }

    @Test
    fun `should complain when dictionary file provided in config does not exist for V2`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("api.yaml")
        val dictionaryFile = tempDir.resolve("dictionary.yaml")
        val configFile = tempDir.resolve("specmatic.yaml")

        specFile.writeText(
        """
        openapi: 3.0.0
        info:
          title: Sample
          version: 1.0.0
        paths:
          /health:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent())

        configFile.writeText(
            yamlMapper.writeValueAsString(
                SpecmaticConfigV2(
                    version = SpecmaticConfigVersion.VERSION_2,
                    contracts = listOf(
                        ContractConfig(
                            filesystem = ContractConfig.FileSystemContractSource(tempDir.canonicalPath),
                            provides = listOf(SpecExecutionConfig.StringValue(specFile.name))
                        )
                    )
                )
            )
        )

        Flags.using(SPECMATIC_STUB_DICTIONARY to dictionaryFile.canonicalPath) {
            assertThatCode { loadTestScenariosWithConfig(configFile.canonicalPath, specFile.canonicalPath) }
                .hasMessageContaining("Expected dictionary file at")
                .hasMessageContaining(dictionaryFile.canonicalPath)
                .hasMessageContaining("but it does not exist")
        }
    }

    @Test
    fun `should complain when dictionary file provided in config does not exist for V3`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("api.yaml")
        val dictionaryFile = tempDir.resolve("dictionary.yaml")
        val configFile = tempDir.resolve("specmatic-v3.yaml")

        specFile.writeText(
        """
        openapi: 3.0.0
        info:
          title: Sample
          version: 1.0.0
        paths:
          /health:
            get:
              responses:
                '200':
                  description: OK
        """.trimIndent())

        configFile.writeText(
            yamlMapper.writeValueAsString(
                SpecmaticConfigV3(
                    version = SpecmaticConfigVersion.VERSION_3,
                    systemUnderTest = TestServiceConfig(
                        service = RefOrValue.Value(
                            CommonServiceConfig(
                                data = Data(dictionary = RefOrValue.Value(Dictionary(path = dictionaryFile.canonicalPath))),
                                definitions = listOf(
                                    Definition(
                                        Definition.Value(
                                            source = RefOrValue.Value(SourceV3.create(filesystem = SourceV3.FileSystem(tempDir.canonicalPath))),
                                            specs = listOf(SpecificationDefinition.StringValue(specFile.name))
                                        )
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )

        assertThatCode { loadTestScenariosWithConfig(configFile.canonicalPath, specFile.canonicalPath) }
            .hasMessageContaining("Expected dictionary file at")
            .hasMessageContaining(dictionaryFile.canonicalPath)
            .hasMessageContaining("but it does not exist")
    }

    private fun runContractTestWithConfig(configFilePath: String): String {
        SpecmaticJUnitSupport.settingsStaging.set(
            ContractTestSettings(
                configFile = configFilePath
            )
        )
        return try {
            val originalOut = System.out
            val outputStream = java.io.ByteArrayOutputStream()
            System.setOut(PrintStream(outputStream))
            try {
                SpecmaticJUnitSupport().contractTest()
            } finally {
                System.out.flush()
                System.setOut(originalOut)
            }
            outputStream.toString()
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    private fun loadTestScenariosWithConfig(configFilePath: String, specFilePath: String) {
        SpecmaticJUnitSupport.settingsStaging.set(ContractTestSettings(configFile = configFilePath))
        try {
            SpecmaticJUnitSupport().loadTestScenarios(
                path = specFilePath,
                suggestionsPath = "",
                suggestionsData = "",
                config = TestConfig(emptyMap(), emptyMap()),
                filterName = null,
                filterNotName = null,
                filter = ScenarioMetadataFilter.from("")
            )
        } finally {
            SpecmaticJUnitSupport.settingsStaging.remove()
        }
    }

    private fun runCommand(workingDirectory: File, vararg command: String) {
        val process = ProcessBuilder(*command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertThat(exitCode)
            .withFailMessage("Command failed: ${command.joinToString(" ")}\n$output")
            .isEqualTo(0)
    }

    private fun writeSpecmaticConfig(tempDir: File, baseUrl: String? = null, maxTestCount: Int? = null): File {
        val configFile = tempDir.resolve("specmatic.yaml")
        val config = SpecmaticConfigV3(
            version = SpecmaticConfigVersion.VERSION_3,
            systemUnderTest = TestServiceConfig(
                service = RefOrValue.Value(
                    CommonServiceConfig(
                        definitions = emptyList(),
                        runOptions = RefOrValue.Value(TestRunOptions(openapi = OpenApiTestConfig(baseUrl = baseUrl))),
                        settings = RefOrValue.Value(TestSettings(maxTestCount = maxTestCount))
                    )
                )
            )
        )
        configFile.writeText(yamlMapper.writeValueAsString(config))
        return configFile
    }

    private fun startAlphaBetaStubServer(): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/alpha") { exchange ->
            val bytes = """{"value":"alpha"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.createContext("/beta") { exchange ->
            val bytes = """{"value":"beta"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.start()
        val baseUrl = "http://localhost:${server.address.port}"
        return server to baseUrl
    }

    private fun captureStdout(block: () -> Unit): String {
        val originalOut = System.out
        val outputStream = java.io.ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
        return try {
            block()
            outputStream.toString()
        } finally {
            System.out.flush()
            System.setOut(originalOut)
        }
    }

    private class RecordingExampleErrorsListener : TestReportListener {
        val exampleErrorsCalls = mutableListOf<Map<String, Result>>()
        override fun onExampleErrors(resultsBySpecFile: Map<String, Result>) {
            exampleErrorsCalls.add(resultsBySpecFile)
        }

        override fun onActuator(enabled: Boolean) = Unit
        override fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>) = Unit
        override fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>) = Unit
        override fun onTestResult(result: io.specmatic.test.reports.TestExecutionResult) = Unit
        override fun onTestsComplete() = Unit
        override fun onEnd() = Unit
        override fun onCoverageCalculated(coverage: Int) = Unit
        override fun onPathCoverageCalculated(path: String, pathCoverage: Int) = Unit
        override fun onGovernance(result: Result) = Unit
        override fun onTestDecision(decision: Decision<Pair<ContractTest, String>, Scenario>) = Unit
    }

    private class RecordingTestDecisionListener : TestReportListener {
        val decisions = mutableListOf<Decision<Pair<ContractTest, String>, Scenario>>()

        override fun onTestDecision(decision: Decision<Pair<ContractTest, String>, Scenario>) {
            decisions.add(decision)
        }

        override fun onEndpointApis(endpointsNotExcluded: List<Endpoint>, endpointsExcluded: List<Endpoint>) = Unit
        override fun onActuatorApis(apisNotExcluded: List<API>, apisExcluded: List<API>) = Unit
        override fun onPathCoverageCalculated(path: String, pathCoverage: Int) = Unit
        override fun onExampleErrors(resultsBySpecFile: Map<String, Result>) = Unit
        override fun onTestResult(result: TestExecutionResult) = Unit
        override fun onCoverageCalculated(coverage: Int) = Unit
        override fun onActuator(enabled: Boolean) = Unit
        override fun onGovernance(result: Result) = Unit
        override fun onTestsComplete() = Unit
        override fun onEnd() = Unit
    }

    @AfterEach
    fun tearDown() {
        SpecmaticJUnitSupport.settingsStaging.remove()
        System.getProperties().keys.minus(initialPropertyKeys).forEach { println("Clearing $it"); System.clearProperty(it.toString()) }
    }
}

private fun Iterable<Decision<ContractTest, *>>.executedTestDescriptions(): List<String> {
    return this.mapNotNull { decision ->
        if (decision is Decision.Execute) decision.value.testDescription()
        else null
    }
}

private fun Sequence<Decision<ContractTest, *>>.executedTestDescriptions(): List<String> {
    return this.mapNotNull { decision ->
        if (decision is Decision.Execute) decision.value.testDescription()
        else null
    }.toList()
}
