package application.mcp.server.tools

import application.TestCommand
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.File

class ContractTestToolTest {

    private val tool = ContractTestTool()

    @AfterEach
    fun tearDown() {
        unmockkAll()
        System.clearProperty(SPECMATIC_GENERATIVE_TESTS)
        File("build/reports/specmatic/test/ctrf-report.json").delete()
    }

    @Test
    fun `runContractTest should format results correctly for a successful contract test`() {
        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } returns 0

        // Mock CTRF report
        val reportDir = File("build/reports/specmatic/test")
        reportDir.mkdirs()
        val reportFile = reportDir.resolve("ctrf-report.json")
        reportFile.writeText("""
            {
              "results": {
                "summary": { "tests": 1, "passed": 1, "failed": 0 },
                "tests": []
              }
            }
        """.trimIndent())

        val args = RunTestArgs(
            openApiSpec = "openapi: 3.0.0...",
            apiBaseUrl = "http://localhost:8080"
        )

        val result = tool.runContractTest(args, resiliency = false)

        assertThat(result).contains("## Specmatic Contract Test Results")
        assertThat(result).contains("Status: PASSED")
        assertThat(result).contains("| Total Tests | 1 |")
        assertThat(result).contains("| Passed | 1 |")
        assertThat(result).contains("| Failed | 0 |")
        assertThat(System.getProperty(SPECMATIC_GENERATIVE_TESTS)).isNull()
    }

    @Test
    fun `runContractTest should format results correctly for a failed resiliency test`() {
        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } returns 1

        // Mock CTRF report
        val reportDir = File("build/reports/specmatic/test")
        reportDir.mkdirs()
        val reportFile = reportDir.resolve("ctrf-report.json")
        reportFile.writeText("""
            {
              "results": {
                "summary": { "tests": 2, "passed": 1, "failed": 1 },
                "tests": [
                  { "name": "Failed Scenario", "status": "failed", "message": "Expected 200 but got 400" }
                ]
              }
            }
        """.trimIndent())

        val args = RunTestArgs(
            openApiSpec = "openapi: 3.0.0...",
            apiBaseUrl = "http://localhost:8080"
        )

        val result = tool.runContractTest(args, resiliency = true)

        assertThat(result).contains("## Specmatic Resiliency Test Results")
        assertThat(result).contains("> Boundary condition testing is enabled")
        assertThat(result).contains("Status: FAILED")
        assertThat(result).contains("| Total Tests | 2 |")
        assertThat(result).contains("| Passed | 1 |")
        assertThat(result).contains("| Failed | 1 |")
        assertThat(result).contains("### Failed Scenarios")
        assertThat(result).contains("- **Failed Scenario**: `Expected 200 but got 400`")
        
        // Ensure generative tests flag was set and then restored/cleared
        // Note: The property check inside runContractTest finally block will clear it if it was null
    }

    @Test
    fun `runContractTest should handle missing CTRF report gracefully`() {
        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } returns 0

        // Ensure report file does not exist
        File("build/reports/specmatic/test/ctrf-report.json").delete()

        val args = RunTestArgs(
            openApiSpec = "openapi: 3.0.0...",
            apiBaseUrl = "http://localhost:8080"
        )

        val result = tool.runContractTest(args, resiliency = false)

        assertThat(result).contains("## Specmatic Contract Test Results")
        assertThat(result).contains("Status: PASSED")
        assertThat(result).doesNotContain("Execution Summary")
    }

    
    @Test
    fun `parseCtrfSummary should correctly parse a valid CTRF report`(@TempDir tempDir: File) {
        val reportFile = tempDir.resolve("ctrf-report.json")
        reportFile.writeText("""
            {
              "results": {
                "summary": {
                  "tests": 10,
                  "passed": 8,
                  "failed": 2,
                  "pending": 0,
                  "skipped": 0,
                  "other": 0,
                  "start": 1715850000000,
                  "stop": 1715850010000
                },
                "tests": [
                  {
                    "name": "Scenario 1",
                    "status": "passed",
                    "duration": 100
                  },
                  {
                    "name": "Scenario 2",
                    "status": "failed",
                    "message": "Error message 2"
                  },
                  {
                    "name": "Scenario 3",
                    "status": "failed",
                    "message": "Error message 3"
                  }
                ]
              }
            }
        """.trimIndent())

        val summary = tool.parseCtrfSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(10)
        assertThat(summary.passed).isEqualTo(8)
        assertThat(summary.failed).isEqualTo(2)
        assertThat(summary.failedTests).hasSize(2)
        assertThat(summary.failedTests[0].scenario).isEqualTo("Scenario 2")
        assertThat(summary.failedTests[0].message).isEqualTo("Error message 2")
        assertThat(summary.failedTests[1].scenario).isEqualTo("Scenario 3")
        assertThat(summary.failedTests[1].message).isEqualTo("Error message 3")
    }

    @Test
    fun `parseCtrfSummary should return null if report file does not exist`() {
        val summary = tool.parseCtrfSummary(File("non-existent.json"))
        assertThat(summary).isNull()
    }

    @Test
    fun `parseCtrfSummary should handle empty failed tests list`(@TempDir tempDir: File) {
        val reportFile = tempDir.resolve("ctrf-report.json")
        reportFile.writeText("""
            {
              "results": {
                "summary": {
                  "tests": 5,
                  "passed": 5,
                  "failed": 0
                },
                "tests": []
              }
            }
        """.trimIndent())

        val summary = tool.parseCtrfSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(5)
        assertThat(summary.passed).isEqualTo(5)
        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.failedTests).isEmpty()
    }

    @Test
    fun `parseCtrfSummary should handle missing summary fields`(@TempDir tempDir: File) {
        val reportFile = tempDir.resolve("ctrf-report.json")
        reportFile.writeText("""
            {
              "results": {
                "summary": {}
              }
            }
        """.trimIndent())

        val summary = tool.parseCtrfSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(0)
        assertThat(summary.passed).isEqualTo(0)
        assertThat(summary.failed).isEqualTo(0)
    }
}
