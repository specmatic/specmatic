package io.specmatic.test

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.TestConfig
import io.specmatic.core.filters.ScenarioMetadataFilter
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.reporter.model.SpecType
import io.specmatic.reporter.model.TestResult
import io.specmatic.test.SpecmaticJUnitSupport.Companion.HOST
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PORT
import io.specmatic.test.SpecmaticJUnitSupport.Companion.PROTOCOL
import io.specmatic.test.SpecmaticJUnitSupport.Companion.TEST_BASE_URL
import io.specmatic.test.listeners.ContractExecutionListener
import io.specmatic.test.reports.coverage.Endpoint
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
                    protocol = "https"
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
                    protocol = "invalid"
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
                    protocol = "https"
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

            val testsWithStrictModeList = testsWithStrictMode.toList()
            val testCountWithStrictMode = testsWithStrictModeList.size

            // Only GET /users/{id} should generate tests (has external example)
            assertThat(testCountWithStrictMode).isEqualTo(1)

            // Verify the test name contains the correct endpoint
            val testDescriptionsWithStrictMode = testsWithStrictModeList.map { it.testDescription() }
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

            val testsWithoutStrictModeList = testsWithoutStrictMode.toList()
            val testCountWithoutStrictMode = testsWithoutStrictModeList.size

            // Both endpoints should generate tests when strictMode is false
            assertThat(testCountWithoutStrictMode).isGreaterThan(1)

            // Verify test names include both endpoints
            val testDescriptionsWithoutStrictMode = testsWithoutStrictModeList.map { it.testDescription() }
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

        assertThat(testData.scenarios.map { it.testDescription() }.toList()).doesNotContain(" Scenario: POST /products -> 201 with the request from the example 'SUCCESS'")
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

    @AfterEach
    fun tearDown() {
        System.getProperties().keys.minus(initialPropertyKeys).forEach { println("Clearing $it"); System.clearProperty(it.toString()) }
    }
}
