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
    fun `runContractTest should invoke TestCommand with generated spec and junit report directory`() {
        var capturedArgs: List<String> = emptyList()
        var generatedSpecFileName = ""
        var generatedSpecFileContent = ""

        mockkConstructor(CommandLine::class)
        every { anyConstructed<CommandLine>().execute(*anyVararg()) } answers {
            capturedArgs = invocation.args.flatMap {
                when (it) {
                    is Array<*> -> it.map { arg -> arg.toString() }
                    else -> listOf(it.toString())
                }
            }

            File(capturedArgs.last()).let {
                generatedSpecFileName = it.name
                generatedSpecFileContent = it.readText()
            }
            val reportFile = reportDir.resolve("TEST-junit-jupiter.xml")
            reportDir.mkdirs()
            reportFile.writeText("""
                <testsuite name="Contract Tests" tests="1" failures="0" errors="0" skipped="0">
                  <testcase name="Scenario 1" classname="Contract Tests" time="0.1"/>
                </testsuite>
            """.trimIndent())
            0
        }

        val specContent = "openapi: 3.0.0\ninfo:\n  title: API\n  version: 1.0.0\npaths: {}"
        val args = RunTestArgs(
            openApiSpec = specContent,
            apiBaseUrl = "http://localhost:8080",
            specFormat = "yaml"
        )

        val result = tool.runContractTest(args, resiliency = false)

        assertThat(capturedArgs).containsSequence("--testBaseURL", "http://localhost:8080")
        assertThat(capturedArgs).containsSequence("--junitReportDir", reportDir.canonicalPath)
        assertThat(generatedSpecFileName).isEqualTo("spec.yaml")
        assertThat(generatedSpecFileContent).isEqualTo(specContent)
        assertThat(result).contains("## Specmatic Contract Test Results")
        assertThat(result).contains("Status: PASSED")
        assertThat(result).contains("| Total Tests | 1 |")
        assertThat(result).contains("| Passed | 1 |")
        assertThat(result).contains("| Failed | 0 |")
        assertThat(System.getProperty(SPECMATIC_GENERATIVE_TESTS)).isNull()
    }

    @Test
    fun `runContractTest should enable generative tests only during resiliency execution`() {
        var generativeFlagDuringExecution: String? = null

        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } answers {
            generativeFlagDuringExecution = System.getProperty(SPECMATIC_GENERATIVE_TESTS)
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
        assertThat(generativeFlagDuringExecution).isEqualTo("true")
        assertThat(System.getProperty(SPECMATIC_GENERATIVE_TESTS)).isNull()
    }

    @Test
    fun `runContractTest should restore SPECMATIC_GENERATIVE_TESTS property even if execution fails`() {
        mockkConstructor(TestCommand::class)
        every { anyConstructed<TestCommand>().call() } throws RuntimeException("Execution failed")
        
        System.setProperty(SPECMATIC_GENERATIVE_TESTS, "original_value")

        val args = RunTestArgs(openApiSpec = "...", apiBaseUrl = "...")
        
        try {
            tool.runContractTest(args, resiliency = true)
        } catch (e: Exception) {
            // expected
        }

        assertThat(System.getProperty(SPECMATIC_GENERATIVE_TESTS)).isEqualTo("original_value")
    }

    @Test
    fun `runContractTest should truncate very large console output`() {
        mockkConstructor(TestCommand::class)
        val largeOutput = "A".repeat(5000)

        every { anyConstructed<TestCommand>().call() } answers {
            print(largeOutput)
            0
        }

        val args = RunTestArgs(openApiSpec = "...", apiBaseUrl = "...")
        val result = tool.runContractTest(args, resiliency = false)

        assertThat(result).contains("truncated 1000 characters")
        assertThat(result).contains("A".repeat(4000))
        assertThat(result).doesNotContain("A".repeat(4001))
    }
}
