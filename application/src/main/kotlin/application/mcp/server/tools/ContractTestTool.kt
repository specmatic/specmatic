package application.mcp.server.tools

import application.mcp.utils.JunitReportReader
import application.TestCommand
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import kotlinx.serialization.Serializable
import picocli.CommandLine
import java.io.File
import kotlin.io.path.createTempDirectory

@Serializable
data class RunTestArgs(
    val openApiSpec: String,
    val apiBaseUrl: String,
    val specFormat: String = "yaml"
)

class ContractTestTool(
    private val junitReportDirectoryFactory: (Boolean) -> File = ::createMcpJUnitReportDirectory
) {

    internal fun runContractTest(args: RunTestArgs, resiliency: Boolean): String {
        val tempDir = createTempDirectory(if (resiliency) "specmatic-resiliency-" else "specmatic-contract-").toFile()
        val specFile = tempDir.resolve("spec.${args.specFormat}").apply { writeText(args.openApiSpec) }
        val junitReportDir = junitReportDirectoryFactory(resiliency)
        val junitReportFile = junitReportDir.resolve(JUNIT_REPORT_FILE_NAME)

        val originalGenerativeFlag = System.getProperty(SPECMATIC_GENERATIVE_TESTS)
        if (resiliency) {
            System.setProperty(SPECMATIC_GENERATIVE_TESTS, "true")
        }

        return try {
            val (exitCode, stdout, stderr) = captureStandardStreams {
                val command = TestCommand()
                val argsList = mutableListOf<String>()
                argsList.add("--testBaseURL")
                argsList.add(args.apiBaseUrl)
                argsList.add("--junitReportDir")
                argsList.add(junitReportDir.canonicalPath)
                argsList.add(specFile.canonicalPath)

                CommandLine(command).execute(*argsList.toTypedArray())
            }

            val summary = JunitReportReader().parseJUnitSummary(junitReportFile)

            formatTestResult(
                title = if (resiliency) "Resiliency" else "Contract",
                success = exitCode == 0,
                summary = summary,
                consoleOutput = stdout,
                errors = stderr,
                extraIntro = if (resiliency) "Boundary condition testing is enabled, so Specmatic also generates contract-invalid requests.\n\n" else ""
            )
        } finally {
            if (originalGenerativeFlag != null) {
                System.setProperty(SPECMATIC_GENERATIVE_TESTS, originalGenerativeFlag)
            } else {
                System.clearProperty(SPECMATIC_GENERATIVE_TESTS)
            }
            tempDir.deleteRecursively()
        }
    }



    private fun formatTestResult(
        title: String,
        success: Boolean,
        summary: TestSummary?,
        consoleOutput: String,
        errors: String,
        extraIntro: String = ""
    ): String {
        return buildString {
            append("## Specmatic $title Test Results\n\n")
            if (extraIntro.isNotBlank()) {
                append("> $extraIntro\n\n")
            }

            append("### Status: ")
            append(if (success) "PASSED" else "FAILED")
            append("\n\n")

            summary?.let {
                append("### Execution Summary\n")
                append("| Metric | Value |\n")
                append("| :--- | :--- |\n")
                append("| Total Tests | ${it.total} |\n")
                append("| Passed | ${it.passed} |\n")
                append("| Failed | ${it.failed} |\n\n")

                append("${it.reportLabel} Report: `${it.reportPath}`\n\n")

                if (it.failedTests.isNotEmpty()) {
                    append("### Failed Scenarios\n")
                    it.failedTests.forEach { failedTest ->
                        append("- **${failedTest.scenario}**")
                        if (failedTest.message.isNotBlank()) {
                            append(": `${failedTest.message}`")
                        }
                        append('\n')
                    }
                    append('\n')
                }
            }

            if (consoleOutput.isNotBlank()) {
                append("### Console Output\n")
                append("```text\n")
                append(consoleOutput.trimEnd().take(4000))
                if (consoleOutput.length > 4000) {
                    append("\n... [truncated ${consoleOutput.length - 4000} characters]")
                }
                append("\n```\n\n")
            }

            if (errors.isNotBlank()) {
                append("### Execution Logs\n")
                append("```text\n")
                append(errors.trimEnd())
                append("\n```\n")
            }
        }
    }
}

private const val JUNIT_REPORT_FILE_NAME = "TEST-junit-jupiter.xml"
private const val MCP_JUNIT_REPORT_BASE_DIR = "build/reports/specmatic/junit"

private fun createMcpJUnitReportDirectory(resiliency: Boolean): File {
    val prefix = if (resiliency) "mcp-resiliency" else "mcp-contract"
    val baseDir = File(MCP_JUNIT_REPORT_BASE_DIR).apply { mkdirs() }
    return createTempDirectory(baseDir.toPath(), "$prefix-").toFile()
}

data class TestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val failedTests: List<FailedTest>,
    val reportLabel: String,
    val reportPath: String
)

data class FailedTest(
    val scenario: String,
    val message: String
)
