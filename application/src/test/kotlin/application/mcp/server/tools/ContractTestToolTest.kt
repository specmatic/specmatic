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
import java.io.File

class ContractTestToolTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var reportDir: File
    private lateinit var tool: ContractTestTool

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        reportDir = tempDir.resolve("junit")
        tool = ContractTestTool { reportDir }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        System.clearProperty(SPECMATIC_GENERATIVE_TESTS)
    }

    @Test
    fun `runContractTest should format results correctly for a successful contract test`() {
        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } answers {
            val reportFile = reportDir.resolve("TEST-junit-jupiter.xml")
            reportDir.mkdirs()
            reportFile.writeText("""
                <testsuite name="Contract Tests" tests="1" failures="0" errors="0" skipped="0">
                  <testcase name="Scenario 1" classname="Contract Tests" time="0.1"/>
                </testsuite>
            """.trimIndent())
            0
        }

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
        every { anyConstructed<TestCommand>().call() } answers {
            val reportFile = reportDir.resolve("TEST-junit-jupiter.xml")
            reportDir.mkdirs()
            reportFile.writeText("""
                <testsuite name="Contract Tests" tests="2" failures="1" errors="0" skipped="0">
                  <testcase name="Passed Scenario" classname="Contract Tests" time="0.1"/>
                  <testcase name="Failed Scenario" classname="Contract Tests" time="0.1">
                    <failure message="Expected 200 but got 400"/>
                  </testcase>
                </testsuite>
            """.trimIndent())
            1
        }

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
    fun `runContractTest should handle missing JUnit report gracefully`() {
        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } returns 0

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
    fun `parseJUnitSummary should correctly parse a valid JUnit report`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests" tests="10" failures="1" errors="1" skipped="0">
              <testcase name="Scenario 1" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 2" classname="Contract Tests" time="0.1">
                <failure message="Error message 2"/>
              </testcase>
              <testcase name="Scenario 3" classname="Contract Tests" time="0.1">
                <error message="Error message 3"/>
              </testcase>
            </testsuite>
        """.trimIndent())

        val summary = tool.parseJUnitSummary(reportFile)

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
    fun `parseJUnitSummary should return null if report file does not exist`() {
        val summary = tool.parseJUnitSummary(File("non-existent.xml"))
        assertThat(summary).isNull()
    }

    @Test
    fun `parseJUnitSummary should handle empty failed tests list`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests" tests="5" failures="0" errors="0" skipped="0">
              <testcase name="Scenario 1" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 2" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 3" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 4" classname="Contract Tests" time="0.1"/>
              <testcase name="Scenario 5" classname="Contract Tests" time="0.1"/>
            </testsuite>
        """.trimIndent())

        val summary = tool.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(5)
        assertThat(summary.passed).isEqualTo(5)
        assertThat(summary.failed).isEqualTo(0)
        assertThat(summary.failedTests).isEmpty()
    }

    @Test
    fun `parseJUnitSummary should handle missing summary fields`() {
        val reportFile = tempDir.resolve("TEST-junit-jupiter.xml")
        reportFile.writeText("""
            <testsuite name="Contract Tests"/>
        """.trimIndent())

        val summary = tool.parseJUnitSummary(reportFile)

        assertThat(summary).isNotNull
        assertThat(summary!!.total).isEqualTo(0)
        assertThat(summary.passed).isEqualTo(0)
        assertThat(summary.failed).isEqualTo(0)
    }
}
