package application.mcp.server.tools

import application.TestCommand
import application.mcp.server.utils.McpUtils.captureStandardStreams
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempDirectory

@Serializable
data class RunTestArgs(
    val openApiSpec: String,
    val apiBaseUrl: String,
    val specFormat: String = "yaml"
)

class ContractTestTool {

    internal fun runContractTest(args: RunTestArgs, resiliency: Boolean): String {
        val tempDir = createTempDirectory(if (resiliency) "specmatic-resiliency-" else "specmatic-contract-").toFile()
        val specFile = tempDir.resolve("spec.${args.specFormat}").apply { writeText(args.openApiSpec) }
        val reportDir = tempDir.resolve("reports").apply { mkdirs() }

        val originalGenerativeFlag = System.getProperty(SPECMATIC_GENERATIVE_TESTS)
        if (resiliency) {
            System.setProperty(SPECMATIC_GENERATIVE_TESTS, "true")
        }

        return try {
            val (exitCode, stdout, stderr) = captureStandardStreams {
                val command = TestCommand().apply {
                    contractPaths = listOf(specFile.canonicalPath)
                    testBaseURL = args.apiBaseUrl
                    junitReportDirName = reportDir.canonicalPath
                }
                command.call()
            }

            val reportFile = reportDir.resolve("TEST-junit-jupiter.xml")
            val summary = if (reportFile.isFile) parseJUnitSummary(reportFile) else null

            formatTestResult(
                title = if (resiliency) "Resiliency" else "Contract",
                success = exitCode == 0,
                summary = summary,
                consoleOutput = stdout,
                errors = stderr,
                reportPath = reportFile.takeIf(File::isFile)?.canonicalPath,
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

    private fun parseJUnitSummary(reportFile: File): JUnitSummary? {
        if (!reportFile.isFile) return null

        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val document = builder.parse(reportFile)
        val suite = document.documentElement ?: return null
        val tests = suite.getAttribute("tests").toIntOrNull() ?: 0
        val failures = suite.getAttribute("failures").toIntOrNull() ?: 0
        val errors = suite.getAttribute("errors").toIntOrNull() ?: 0
        val failed = failures + errors
        val passed = (tests - failed).coerceAtLeast(0)

        val failedTests = mutableListOf<FailedJUnitTest>()
        val testcases = suite.getElementsByTagName("testcase")
        for (index in 0 until testcases.length) {
            val testcase = testcases.item(index) as? Element ?: continue
            val failureNode = testcase.getElementsByTagName("failure").item(0) as? Element
            val errorNode = testcase.getElementsByTagName("error").item(0) as? Element
            val node = failureNode ?: errorNode ?: continue

            failedTests += FailedJUnitTest(
                scenario = testcase.getAttribute("name").ifBlank {
                    listOf(testcase.getAttribute("classname"), testcase.getAttribute("name"))
                        .filter { it.isNotBlank() }
                        .joinToString(".")
                },
                message = node.getAttribute("message").ifBlank { node.textContent.orEmpty().trim() }
            )
        }

        return JUnitSummary(
            total = tests,
            passed = passed,
            failed = failed,
            failedTests = failedTests
        )
    }

    private fun formatTestResult(
        title: String,
        success: Boolean,
        summary: JUnitSummary?,
        consoleOutput: String,
        errors: String,
        reportPath: String?,
        extraIntro: String = ""
    ): String {
        return buildString {
            append("# Specmatic $title Test Results\n\n")
            append(extraIntro)
            append("Status: ")
            append(if (success) "PASSED" else "FAILED")
            append("\n\n")

            summary?.let {
                append("Summary:\n")
                append("- Total tests: ${it.total}\n")
                append("- Passed: ${it.passed}\n")
                append("- Failed: ${it.failed}\n\n")

                if (reportPath != null) {
                    append("JUnit report: `$reportPath`\n\n")
                }

                if (it.failedTests.isNotEmpty()) {
                    append("Failed tests:\n")
                    it.failedTests.forEach { failedTest ->
                        append("- ${failedTest.scenario}")
                        if (failedTest.message.isNotBlank()) {
                            append(": ${failedTest.message}")
                        }
                        append('\n')
                    }
                    append('\n')
                }
            }

            if (consoleOutput.isNotBlank()) {
                append("Console output:\n")
                append("```\n")
                append(consoleOutput.trimEnd().take(4000))
                if (consoleOutput.length > 4000) {
                    append("\n... [truncated ${consoleOutput.length - 4000} characters]")
                }
                append("\n```\n\n")
            }

            if (errors.isNotBlank()) {
                append("Errors:\n")
                append("```\n")
                append(errors.trimEnd())
                append("\n```\n")
            }
        }
    }
}

private data class JUnitSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val failedTests: List<FailedJUnitTest>
)

private data class FailedJUnitTest(
    val scenario: String,
    val message: String
)
