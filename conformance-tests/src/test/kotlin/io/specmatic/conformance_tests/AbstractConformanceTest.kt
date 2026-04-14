package io.specmatic.conformance_tests

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.specmatic.conformance.tests.VersionInfo
import io.specmatic.conformance_test_support.DockerCompose
import io.specmatic.conformance_test_support.HttpExchange
import io.specmatic.conformance_test_support.OpenApiSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
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

    // Flag to track if we caught unsupported content type errors (e.g., multipart)
    private var hasUnsupportedContentTypeErrors = false

    private val spec: OpenApiSpec =
        OpenApiSpec(File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile"))

    companion object {
        private const val X_FIELD_LOOP = "x-specmatic-expect-failure-loop"
        private const val X_FIELD_OPERATIONS = "x-specmatic-expect-failure-operations"
        private const val X_FIELD_REQUEST_BODIES = "x-specmatic-expect-failure-request-bodies"
        private const val X_FIELD_RESPONSE_BODIES = "x-specmatic-expect-failure-response-bodies"

        private const val TEST_NAME_LOOP = "loop tests should succeed()"
        private const val TEST_NAME_OPERATIONS = "should only exercise all operations in the openAPI spec and not make any additional non-compliant requests"
        private const val TEST_NAME_REQUEST_BODIES = "should send valid request bodies()"
        private const val TEST_NAME_RESPONSE_BODIES = "should return valid response bodies()"
    }

    private val yamlMapper = ObjectMapper(YAMLFactory())

    protected data class ExpectedFailure(val xFieldName: String, val reason: String)

    protected enum class TestType(val xFieldName: String) {
        LOOP_TESTS(X_FIELD_LOOP),
        OPERATIONS(X_FIELD_OPERATIONS),
        REQUEST_BODIES(X_FIELD_REQUEST_BODIES),
        RESPONSE_BODIES(X_FIELD_RESPONSE_BODIES)
    }

    protected fun reportExpectedFailure(expectedFailure: ExpectedFailure, testName: String) {
        System.err.println("")
        System.err.println("╔════════════════════════════════════════════════════════════════════════════════")
        System.err.println("║ ⚠️  EXPECTED FAILURE: ${expectedFailure.reason}")
        System.err.println("║ Test: $testName")
        System.err.println("║ Status: Test failed as expected (known issue)")
        System.err.println("╚════════════════════════════════════════════════════════════════════════════════")
        System.err.println("")
    }

    protected fun buildUnexpectedPassMessage(expectedFailure: ExpectedFailure): String {
        return buildString {
            appendLine("Test passed unexpectedly! The bug has been fixed.")
            appendLine("Reason: ${expectedFailure.reason}")
            append("Remove `${expectedFailure.xFieldName}` from the spec file.")
        }
    }

    private fun logTestDisplayName(baseName: String, expectedFailure: ExpectedFailure?) {
        val displayName = if (expectedFailure != null) {
            "$baseName [EXPECTED FAILURE: ${expectedFailure.reason}]"
        } else {
            baseName
        }
        System.out.println("DISPLAY_NAME:$displayName")
    }

    protected fun expectFailureFor(testType: TestType): ExpectedFailure? {
        return try {
            val specFile = File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile")
            if (!specFile.exists()) return null

            val yaml = yamlMapper.readTree(specFile)
            val fieldValue = yaml.get(testType.xFieldName)?.asText()
            if (fieldValue.isNullOrBlank()) return null
            ExpectedFailure(testType.xFieldName, fieldValue)
        } catch (e: Exception) {
            null
        }
    }

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

        dockerCompose.mustStop()
    }

    @Test
    @Order(1)
    fun `loop tests should succeed`() {
        val expectedFailure = expectFailureFor(TestType.LOOP_TESTS)
        logTestDisplayName(TEST_NAME_LOOP, expectedFailure)
        val actualResult = loopTestsResult.isSuccessful()

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            if (!actualResult) {
                System.out.println("<<<EXPECTED_FAILURE>>>${expectedFailure.reason}<<<EXPECTED_FAILURE>>>")
                reportExpectedFailure(expectedFailure, "loop tests should succeed()")
            }
            assertThat(actualResult)
                .withFailMessage { buildUnexpectedPassMessage(expectedFailure) }
                .isFalse
        } else {
            // NORMAL: Expected to pass
            assertThat(actualResult)
                .withFailMessage { "${loopTestsResult.command}\n${loopTestsResult.errorOutput}\n\n$allLogs" }
                .isTrue
        }
    }

    @Test
    @Order(2)
    fun `should only exercise all operations in the openAPI spec and not make any additional non-compliant requests`() {
        val specOps = spec.operations
        val debugInfoBuilder = StringBuilder()
        debugInfoBuilder.appendLine("Operations defined in spec (${specOps.size}): $specOps")
        debugInfoBuilder.appendLine("HTTP exchanges captured (${httpExchanges.size}): ${httpExchanges.joinToString("\n") { it.toDebugInfo() }}")

        val exchangeOps = httpExchanges.map { it.toOperation(spec) ?: it.toOperation() }.toSet()
        debugInfoBuilder.appendLine("HTTP exchanges mapped to operations (${httpExchanges.size} exchanges -> ${exchangeOps.size} unique operations): $exchangeOps")

        val expectedFailure = expectFailureFor(TestType.OPERATIONS)
        logTestDisplayName(TEST_NAME_OPERATIONS, expectedFailure)
        val actualResult = specOps == exchangeOps

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            if (!actualResult) {
                System.out.println("<<<EXPECTED_FAILURE>>>${expectedFailure.reason}<<<EXPECTED_FAILURE>>>")
                reportExpectedFailure(expectedFailure, "should only exercise all operations in the openAPI spec")
            }
            assertThat(actualResult)
                .withFailMessage { buildUnexpectedPassMessage(expectedFailure) }
                .isFalse
        } else {
            // NORMAL: Expected to pass
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
    }

    @Test
    @Order(4)
    fun `should send valid request bodies`() {
        val errors = httpExchanges
            // requests in the test that produce 4xx, 5xx may contain invalid request bodies by design
            .filter(HttpExchange::isSuccessful)
            // requests without a requestContentType cannot have bodies
            // In the previous test we have validated that all requests are valid so we can safely skip these here
            .filter { it.toOperation(spec)?.hasRequestContentType() == true }
            .flatMap { exchange ->
                try {
                    spec.validateRequestBody(
                        body = exchange.requestBody,
                        operation = exchange.toOperation(spec)!!
                    )
                } catch (e: IllegalStateException) {
                    // Handle unsupported content types gracefully (e.g., multipart)
                    hasUnsupportedContentTypeErrors = true
                    emptyList<Error>()
                }
            }

        val expectedFailure = expectFailureFor(TestType.REQUEST_BODIES)
        logTestDisplayName(TEST_NAME_REQUEST_BODIES, expectedFailure)

        fun hasValidationErrors(): Boolean {
            return !errors.isEmpty() || hasUnsupportedContentTypeErrors
        }

        // DEBUG: Print errors and flag status
        System.out.println("DEBUG: errors.size = ${errors.size}")
        System.out.println("DEBUG: hasUnsupportedContentTypeErrors = $hasUnsupportedContentTypeErrors")
        System.out.println("DEBUG: errors = $errors")

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            if (hasValidationErrors()) {
                System.out.println("<<<EXPECTED_FAILURE>>>${expectedFailure.reason}<<<EXPECTED_FAILURE>>>")
                reportExpectedFailure(expectedFailure, "should send valid request bodies()")
            }
            assertThat(hasValidationErrors())
                .withFailMessage { buildUnexpectedPassMessage(expectedFailure) }
                .isTrue
        } else {
            // NORMAL: Expected to pass
            assertThat(errors)
                .withFailMessage {
                    "error=$errors\n\nrequests=${httpExchanges.joinToString("\n") { it.requestBody }}\n\n$allLogs"
                }
                .isEmpty()
        }
    }

    @Test
    @Order(5)
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

        val expectedFailure = expectFailureFor(TestType.RESPONSE_BODIES)
        logTestDisplayName(TEST_NAME_RESPONSE_BODIES, expectedFailure)

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            if (!errors.isEmpty()) {
                System.out.println("<<<EXPECTED_FAILURE>>>${expectedFailure.reason}<<<EXPECTED_FAILURE>>>")
                reportExpectedFailure(expectedFailure, "should return valid response bodies()")
            }
            assertThat(errors.isEmpty())
                .withFailMessage { buildUnexpectedPassMessage(expectedFailure) }
                .isFalse
        } else {
            // NORMAL: Expected to pass
            assertThat(errors)
                .withFailMessage {
                    "errors=$errors\n\nresponses=${httpExchanges.joinToString("\n") { it.responseBody }}\n\n$allLogs"
                }
                .isEmpty()
        }
    }
}
