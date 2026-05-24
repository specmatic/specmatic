package application.mcp.server.tools

import application.TestCommand
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import java.io.File
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
                val command = TestCommand()
                val argsList = mutableListOf<String>()
                argsList.add("--testBaseURL")
                argsList.add(args.apiBaseUrl)
                argsList.add("--junitReportDir")
                argsList.add(reportDir.canonicalPath)
                argsList.add(specFile.canonicalPath)

                CommandLine(command).execute(*argsList.toTypedArray())
            }

            val summary = reportDir.resolve(DEFAULT_CTRF_REPORT_PATH).let(::parseCtrfSummary)

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

    internal fun parseCtrfSummary(reportFile: File): TestSummary? {
        if (!reportFile.isFile) return null

        val results = Json.parseToJsonElement(reportFile.readText()).jsonObject["results"]?.jsonObject ?: return null
        val summary = results["summary"]?.jsonObject ?: return null

        val total = summary["tests"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val passed = summary["passed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val failed = summary["failed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val failedTests = results["tests"]
            ?.jsonArray
            ?.mapNotNull { test ->
                val testObject = test.jsonObject
                if (testObject["status"]?.jsonPrimitive?.content != "failed") return@mapNotNull null

                FailedTest(
                    scenario = testObject["name"]?.jsonPrimitive?.content.orEmpty(),
                    message = testObject["message"]?.jsonPrimitive?.content.orEmpty()
                )
            }
            .orEmpty()

        return TestSummary(
            total = total,
            passed = passed,
            failed = failed,
            failedTests = failedTests,
            reportLabel = "CTRF",
            reportPath = reportFile.canonicalPath
        )
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

                append("Report: `${it.reportPath}`\n\n")

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
                append("### Errors\n")
                append("```text\n")
                append(errors.trimEnd())
                append("\n```\n")
            }
        }
    }
}

private const val DEFAULT_CTRF_REPORT_PATH = "build/reports/specmatic/test/ctrf-report.json"

internal data class TestSummary(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val failedTests: List<FailedTest>,
    val reportLabel: String,
    val reportPath: String
)

internal data class FailedTest(
    val scenario: String,
    val message: String
)
