package io.specmatic.conformance_tests

import io.specmatic.conformance.tests.VersionInfo
import io.specmatic.conformance_test_support.DockerCompose
import io.specmatic.conformance_test_support.HttpExchange
import io.specmatic.conformance_test_support.OpenApiSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
abstract class AbstractConformanceTest(
    private val openAPISpecFile: String,
    private val workDir: File = File("build/resources/test"),
    private val specsDirName: String = "specs"
) {
    private lateinit var dockerCompose: DockerCompose
    private lateinit var loopTestsResult: DockerCompose.CommandResult
    private lateinit var allLogs: String
    private lateinit var httpExchanges: List<HttpExchange>

    private val spec: OpenApiSpec =
        OpenApiSpec(File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile"))

    @RegisterExtension
    val expectedFailureExtension = ExpectedFailureExtension { tag -> spec.findExtensionByKey(tag) }

    @BeforeAll
    fun setup() {
        val specmaticVersionForConformanceTests = System.getProperty("specmaticVersionForConformanceTests")
        dockerCompose = DockerCompose(
            specmaticVersion = when {
                specmaticVersionForConformanceTests.isNullOrBlank() -> VersionInfo.version
                else -> specmaticVersionForConformanceTests
            },
            mitmProxyVersion = "12.2.1",
            pathToOpenAPISpecFile = openAPISpecFile,
            workDir = workDir,
            specsDirName = specsDirName
        )
        loopTestsResult = dockerCompose.runLoopTests()
        allLogs = dockerCompose.mustGetAllLogs()
        httpExchanges =
            HttpExchange.parseAll(dockerCompose.mustGetHttpTrafficLogs())
                .filterNot(HttpExchange::isInfraRequest)
    }

    @AfterAll
    fun tearDown() {
        dockerCompose.mustStop()
    }

    @Test
    @Order(1)
    @ExpectFailureTag("x-specmatic-expect-failure-loop")
    fun `loop tests should succeed`() {
        assertThat(loopTestsResult.isSuccessful())
            .withFailMessage { "${loopTestsResult.command}\n${loopTestsResult.errorOutput}\n\n$allLogs" }
            .isTrue
    }

    @Test
    @Order(2)
    @ExpectFailureTag("x-specmatic-expect-failure-operations")
    fun `should only exercise all operations in the openAPI spec and not make any additional non-compliant requests`() {
        val specOps = spec.operations
        val debugInfoBuilder = StringBuilder()
        debugInfoBuilder.appendLine("Operations defined in spec (${specOps.size}): $specOps")
        debugInfoBuilder.appendLine("HTTP exchanges captured (${httpExchanges.size}): ${httpExchanges.joinToString("\n") { it.toDebugInfo() }}")

        val exchangeOps = httpExchanges.map { it.toOperation(spec) ?: it.toOperation() }.toSet()
        debugInfoBuilder.appendLine("HTTP exchanges mapped to operations (${httpExchanges.size} exchanges -> ${exchangeOps.size} unique operations): $exchangeOps")

        assertThat(specOps)
            .withFailMessage {
                debugInfoBuilder
                    .appendLine("Missing Operations: ${specOps - exchangeOps}")
                    .appendLine("Extra Operations: ${exchangeOps - specOps}")
                    .appendLine()
                    .appendLine(allLogs)
                    .toString()
            }
            .isEqualTo(exchangeOps)
    }

    @Test
    @Order(3)
    @ExpectFailureTag("x-specmatic-expect-failure-query-parameters")
    fun `should send valid query parameters`() {
        val errors = httpExchanges
            // requests in the test that produce 4xx, 5xx may contain invalid query parameters by design
            .filter(HttpExchange::isSuccessful)
            // Only validate query parameters for operations defined in the spec.
            .filterNot { it.toOperation(spec) == null }
            .flatMap {
                spec.validateQueryParameters(
                    url = it.url,
                    operation = it.toOperation(spec)!!
                )
            }

        assertThat(errors)
            .withFailMessage {
                "errors=$errors\n\nurls=${httpExchanges.joinToString("\n") { it.url }}\n\n$allLogs"
            }
            .isEmpty()
    }

    @Test
    @Order(4)
    @ExpectFailureTag("x-specmatic-expect-failure-request-bodies")
    fun `should send valid request bodies`() {
        val errors = httpExchanges
            // requests in the test that produce 4xx, 5xx may contain invalid request bodies by design
            .filter(HttpExchange::isSuccessful)
            // requests without a requestContentType cannot have bodies
            // In the previous test we have validated that all requests are valid so we can safely skip these here
            .filter { it.toOperation(spec)?.hasRequestContentType() == true }
            .flatMap {
                spec.validateRequestBody(
                    body = it.requestBody,
                    operation = it.toOperation(spec)!!
                )
            }

        assertThat(errors)
            .withFailMessage {
                "error=$errors\n\nrequests=${httpExchanges.joinToString("\n") { it.requestBody }}\n\n$allLogs"
            }
            .isEmpty()
    }

    @Test
    @Order(5)
    @ExpectFailureTag("x-specmatic-expect-failure-response-bodies")
    fun `should return valid response bodies`() {
        val errors = httpExchanges
            // Only validate responses for operations defined in the spec.
            // When Specmatic's mock rejects a request, it returns a 400 text/plain error that isn't
            // in the spec — attempting to resolve its schema pointer would throw InvalidSchemaRefException.
            // Those failures are already caught by the "loop tests should succeed" test.
            .filterNot { it.toOperation(spec) == null }
            // http exchanges without a response are excluded from this validation.
            // In the test number 2 (@Order = 2) we have validated that all requests are spec compliant
            .filterNot { it.responseContentType == null }
            .flatMap {
                spec.validateResponseBody(
                    body = it.responseBody,
                    operation = it.toOperation(spec)!!,
                    responseContentType = it.responseContentType!!
                )
            }

        assertThat(errors)
            .withFailMessage {
                "errors=$errors\n\nresponses=${httpExchanges.joinToString("\n") { it.responseBody }}\n\n$allLogs"
            }
            .isEmpty()
    }

    @Test
    @Order(6)
    @ExpectFailureTag("x-specmatic-expect-failure-request-headers")
    fun `should send valid request headers`() {
        val errors = httpExchanges
            // requests in the test that produce 4xx, 5xx may contain invalid headers by design
            .filter(HttpExchange::isSuccessful)
            // Only validate headers for operations defined in the spec.
            .filterNot { it.toOperation(spec) == null }
            .flatMap {
                spec.validateRequestHeaders(
                    headers = it.requestHeaders,
                    operation = it.toOperation(spec)!!
                )
            }

        assertThat(errors)
            .withFailMessage {
                "errors=$errors\n\nrequestHeaders=${httpExchanges.joinToString("\n") { it.requestHeaders.toString() }}\n\n$allLogs"
            }
            .isEmpty()
    }

    @Test
    @Order(7)
    @ExpectFailureTag("x-specmatic-expect-failure-response-headers")
    fun `should return valid response headers`() {
        val errors = httpExchanges
            // Only validate responses for operations defined in the spec.
            .filterNot { it.toOperation(spec) == null }
            .flatMap {
                spec.validateResponseHeaders(
                    headers = it.responseHeaders,
                    operation = it.toOperation(spec)!!
                )
            }

        assertThat(errors)
            .withFailMessage {
                "errors=$errors\n\nresponseHeaders=${httpExchanges.joinToString("\n") { it.responseHeaders.toString() }}\n\n$allLogs"
            }
            .isEmpty()
    }
}
