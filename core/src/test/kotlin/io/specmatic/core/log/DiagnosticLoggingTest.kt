package io.specmatic.core.log

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.examples.source.DirectoryExampleSource
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.mock.FuzzyExampleJsonValidator
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DiagnosticLoggingTest {
    @AfterEach
    fun resetDefaults() {
        setDefaultDiagnosticLogger(NoOpDiagnosticLogger)
        System.clearProperty("SPECMATIC_NEW_LOGGER")
        resetLogger()
    }

    @Test
    fun `diagnostic logger renders filter information only when present`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                TestFlowDiagnostics.testRunStarted("name=/products")
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Applying test filters")
        assertThat(plainText).contains("Filters: name=/products")
        assertThat(plainText).doesNotContain("Preparing Tests")
        assertThat(plainText).doesNotContain("Starting contract test run")
        assertThat(plainText).doesNotContain("Discovered 1 contract file(s)")
    }

    @Test
    fun `diagnostic logger renders actionable scenario failures`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())
        val failure = Result.Failure(message = "Expected number, actual was string", breadCrumb = "BODY.id").updatePath("contracts/service.yaml")

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                TestFlowDiagnostics.scenarioFailed(
                    name = "Scenario: GET /items -> 200",
                    summary = "The service response did not match the contract",
                    remediation = "Compare the failing breadcrumb and rule violations above with the actual response from the service.",
                    contractPath = "contracts/service.yaml",
                    failure = failure,
                )
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Error: The service response did not match the contract")
        assertThat(plainText).contains("Scenario: Scenario: GET /items -> 200")
        assertThat(plainText).contains("Expected number, actual was string")
        assertThat(plainText).contains("How to fix")
    }

    @Test
    fun `without diagnostic logging suppresses diagnostic output`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                withoutLogging {
                    TestFlowDiagnostics.testRunStarted("name=/items")
                }
            }
        }

        assertThat(stripAnsi(output)).isEmpty()
    }

    @Test
    fun `diagnostic logger renders low emphasis scenario execution detail`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                TestFlowDiagnostics.scenarioExecutionDetail(
                    name = "Scenario: GET /items -> 200",
                    summary = "Resolved dynamic request data",
                    details = "Authorization header substituted from environment variables.",
                    contractPath = "contracts/service.yaml",
                )
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Resolved dynamic request data")
        assertThat(plainText).contains("Authorization header substituted from environment variables.")
    }

    @Test
    fun `diagnostic logger renders http interactions as scenario detail`() {
        val httpLog = HttpLogMessage(
            request = HttpRequest(method = "GET", path = "/items"),
            response = HttpResponse(status = 200),
            contractPath = "contracts/service.yaml",
            targetServer = "http://localhost:9000",
            scenario = null,
        )

        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(MordantDiagnosticLogger()) {
                withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                    TestFlowDiagnostics.httpInteraction(httpLog)
                }
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] HTTP GET /items -> 200")
        assertThat(plainText).contains("HTTP GET /items -> 200")
        assertThat(plainText).doesNotContain("Target: http://localhost:9000")
        assertThat(plainText).contains("Request")
        assertThat(plainText).contains("Response")
        assertThat(plainText).doesNotContain("Scenario: Unknown Request")
        assertThat(plainText).contains("GET")
        assertThat(plainText).contains("200 OK")
    }

    @Test
    fun `openapi parser warnings render through specification preparation diagnostics when new logger is enabled`() {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        resetLogger()

        val yaml = """
            openapi: 3.0.3
            info:
              title: Simple API
              version: 1.0.0
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          additionalProperties: []
                  responses:
                    '200':
                      description: Success
            """.trimIndent()

        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(MordantDiagnosticLogger()) {
                withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                    OpenApiSpecification.fromYAML(yaml, "spec.yaml")
                }
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Warning: Loaded specification spec.yaml with parser warnings")
        assertThat(plainText).contains("additionalProperties is not of type")
        assertThat(plainText).contains("How to fix")
        assertThat(plainText).doesNotContain("Preprocessed YAML used for parsing")
        assertThat(plainText).doesNotContain("WARNING: The OpenAPI file spec.yaml was read successfully but with some issues")
    }

    @Test
    fun `openapi parser failures render through specification preparation diagnostics when new logger is enabled`() {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        resetLogger()

        val yaml = """
            openapi: 3.0.3
            info:
              title: Broken API
              version: 1.0.0
            paths:
              /data:
                get:
                  responses:
                    '200'
                      description: Success
            """.trimIndent()

        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(MordantDiagnosticLogger()) {
                withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                    kotlin.runCatching {
                        OpenApiSpecification.fromYAML(yaml, "broken-spec.yaml")
                    }
                }
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Error: Could not parse specification broken-spec.yaml")
        assertThat(plainText).contains("while parsing a block mapping")
        assertThat(plainText).contains("validate the contract using https://editor.swagger.io")
        assertThat(plainText).doesNotContain("FATAL: Failed to parse OpenAPI")
    }

    @Test
    fun `directory example source renders malformed example failures through specification preparation diagnostics`(@TempDir tempDir: File) {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        resetLogger()

        val exampleDir = tempDir.resolve("examples").apply { mkdirs() }
        exampleDir.resolve("bad.json").writeText("{invalid json")

        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(MordantDiagnosticLogger()) {
                withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                    DirectoryExampleSource(
                        exampleDirs = listOf(exampleDir.canonicalPath),
                        strictMode = false,
                        specmaticConfig = SpecmaticConfig(),
                    ).examples
                }
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("Loaded 1 external example file(s)")
        assertThat(plainText).contains("[Specmatic::Test] Error: Could not load example bad.json")
        assertThat(plainText).contains("Fix the example JSON structure")
        assertThat(plainText).doesNotContain("Could not load test file")
    }

    @Test
    fun `openapi specification summary and inline example warnings render through preparation diagnostics when new logger is enabled`() {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        resetLogger()

        val specPath = "src/test/resources/openapi/inline_response_example_without_request.yaml"

        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(MordantDiagnosticLogger()) {
                withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                    OpenApiSpecification.fromFile(specPath).toFeature()
                }
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("Loaded specification inline_response_example_without_request.yaml")
        assertThat(plainText).contains("OpenAPI Version: 3.0.3")
        assertThat(plainText).contains("[Specmatic::Test] Warning: Inline example complete_onboarding is incomplete")
        assertThat(plainText).contains("matching response example")
    }

    @Test
    fun `internal helper contracts are suppressed by the producer`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                FuzzyExampleJsonValidator.matches(emptyMap())
            }
        }

        assertThat(stripAnsi(output)).doesNotContain("external_example.yaml")
    }

    @Test
    fun `without logging suppresses preparation diagnostics in new logger mode`() {
        System.setProperty("SPECMATIC_NEW_LOGGER", "true")
        resetLogger()

        val yaml = """
            openapi: 3.0.3
            info:
              title: Simple API
              version: 1.0.0
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          additionalProperties: []
                  responses:
                    '200':
                      description: Success
            """.trimIndent()

        val (output, _) = captureStandardOutput(trim = false) {
            withLogger(MordantDiagnosticLogger()) {
                withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                    withoutLogging {
                        OpenApiSpecification.fromYAML(yaml, "spec.yaml")
                    }
                }
            }
        }

        assertThat(stripAnsi(output)).isEmpty()
    }

    @Test
    fun `example validation failure summary includes invalid external example counts when provided`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())
        val failure = Result.Failure(
            message = "Specification expected type number but example contained value expensive of type string",
            breadCrumb = "REQUEST.BODY.price",
        )

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                SpecificationPreparationDiagnostics.exampleValidationFailed(
                    contractPath = "contracts/spec.yaml",
                    failure = failure,
                    remediation = "Fix the example payloads so they match the contract.",
                    invalidExampleFileCount = 1,
                    totalExampleFileCount = 1,
                )
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Error: 1 of 1 external example is invalid for spec.yaml")
        assertThat(plainText).doesNotContain("Rule Violations")
    }

    @Test
    fun `diagnostic logger renders specification config loading in test mode`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                TestFlowDiagnostics.specificationConfigLoadingStarted("/workspace/specmatic.yaml")
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("[Specmatic::Test] Loading specifications from specmatic.yaml")
        assertThat(plainText).contains("Specification Config: /workspace/specmatic.yaml")
    }

    @Test
    fun `report output renders without a specmatic tag`() {
        setDefaultDiagnosticLogger(MordantDiagnosticLogger())

        val (output, _) = captureStandardOutput(trim = false) {
            withExecutionContext(ExecutionContext(ExecutionMode.TEST)) {
                TestFlowDiagnostics.reportOutputOrFallbackTo(
                    """
                    |--------------------------------------------------------------------------|
                    | SPECMATIC API COVERAGE SUMMARY                                           |
                    |--------------------------------------------------------------------------|
                    """.trimIndent()
                ) {}
            }
        }

        val plainText = stripAnsi(output)
        assertThat(plainText).contains("SPECMATIC API COVERAGE SUMMARY")
        assertThat(plainText).doesNotContain("[Specmatic::Test]")
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\\u001B\\[[;\\d]*m"), "").trim()
    }
}
