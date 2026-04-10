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

    private val spec: OpenApiSpec =
        OpenApiSpec(File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile"))

    companion object {
        private const val X_FIELD_LOOP = "x-specmatic-expect-failure-loop"
        private const val X_FIELD_OPERATIONS = "x-specmatic-expect-failure-operations"
        private const val X_FIELD_REQUEST_BODIES = "x-specmatic-expect-failure-request-bodies"
        private const val X_FIELD_RESPONSE_BODIES = "x-specmatic-expect-failure-response-bodies"
    }

    private val yamlMapper = ObjectMapper(YAMLFactory())

    protected data class ExpectedFailure(val xFieldName: String, val reason: String)

    protected fun expectFailureFor(methodName: String): ExpectedFailure? {
        val xFieldName = when (methodName) {
            "loop tests should succeed" -> X_FIELD_LOOP
            "should only exercise all operations in the openAPI spec and not make any additional non-compliant requests" -> X_FIELD_OPERATIONS
            "should send valid request bodies" -> X_FIELD_REQUEST_BODIES
            "should return valid response bodies" -> X_FIELD_RESPONSE_BODIES
            else -> return null
        }

        return try {
            val specFile = File("${workDir.absolutePath}/${specsDirName}/$openAPISpecFile")
            if (!specFile.exists()) return null

            val yaml = yamlMapper.readTree(specFile)
            val fieldValue = yaml.get(xFieldName)?.asText()
            if (fieldValue.isNullOrBlank()) return null
            ExpectedFailure(xFieldName, fieldValue)
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
        val expectedFailure = expectFailureFor("loop tests should succeed")
        val actualResult = loopTestsResult.isSuccessful()

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            assertThat(actualResult)
                .withFailMessage {
                    buildString {
                        appendLine("Test passed but was expected to fail.")
                        appendLine("Reason: ${expectedFailure.reason}")
                        append("Remove `${expectedFailure.xFieldName}` from the spec file.")
                    }
                }
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

        val expectedFailure = expectFailureFor("should only exercise all operations in the openAPI spec and not make any additional non-compliant requests")
        val actualResult = specOps == exchangeOps

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            assertThat(actualResult)
                .withFailMessage {
                    buildString {
                        appendLine("Test passed but was expected to fail.")
                        appendLine("Reason: ${expectedFailure.reason}")
                        append("Remove `${expectedFailure.xFieldName}` from the spec file.")
                    }
                }
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
            .flatMap {
                spec.validateRequestBody(
                    body = it.requestBody,
                    operation = it.toOperation(spec)!!
                )
            }

        val expectedFailure = expectFailureFor("should send valid request bodies")
        val actualResult = errors.isEmpty()

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            assertThat(actualResult)
                .withFailMessage {
                    buildString {
                        appendLine("Test passed but was expected to fail.")
                        appendLine("Reason: ${expectedFailure.reason}")
                        append("Remove `${expectedFailure.xFieldName}` from the spec file.")
                    }
                }
                .isFalse
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

        val expectedFailure = expectFailureFor("should return valid response bodies")

        if (expectedFailure != null) {
            // INVERTED: Expected to fail
            assertThat(errors.isEmpty())
                .withFailMessage {
                    buildString {
                        appendLine("Test passed but was expected to fail.")
                        appendLine("Reason: ${expectedFailure.reason}")
                        append("Remove `${expectedFailure.xFieldName}` from the spec file.")
                    }
                }
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
