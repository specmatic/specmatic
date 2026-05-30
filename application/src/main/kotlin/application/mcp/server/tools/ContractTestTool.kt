package application.mcp.server.tools

import application.TestCommand
import io.specmatic.core.utilities.Flags.Companion.SPECMATIC_GENERATIVE_TESTS
import io.specmatic.core.utilities.newXMLBuilder
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.Node
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

            val summary = parseJUnitSummary(junitReportFile)

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

    internal fun parseJUnitSummary(reportFile: File): TestSummary? {
        if (!reportFile.isFile) return null

        val root = newXMLBuilder().parse(reportFile).documentElement ?: return null
        if (root.tagName !in setOf("testsuite", "testsuites")) return null

        val testCases = root.descendantElements("testcase")
        val failedTests = testCases.mapNotNull { testCase ->
            val failure = testCase.firstChildElement("failure") ?: testCase.firstChildElement("error") ?: return@mapNotNull null
            FailedTest(
                scenario = testCase.getAttribute("name"),
                message = failure.getAttribute("message").ifBlank { failure.textContent.orEmpty().trim() }
            )
        }

        val total = root.intAttribute("tests") ?: testCases.size
        val failures = root.intAttribute("failures")
        val errors = root.intAttribute("errors")
        val failed = if (failures != null || errors != null) (failures ?: 0) + (errors ?: 0) else failedTests.size
        val skipped = root.intAttribute("skipped") ?: testCases.count { it.firstChildElement("skipped") != null }
        val passed = total - failed - skipped

        return TestSummary(
            total = total,
            passed = passed,
            failed = failed,
            failedTests = failedTests,
            reportLabel = "JUnit",
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
private const val MCP_JUNIT_REPORT_BASE_DIR = "build/reports/specmatic/test/junit"

private fun createMcpJUnitReportDirectory(resiliency: Boolean): File {
    val prefix = if (resiliency) "mcp-resiliency" else "mcp-contract"
    val baseDir = File(MCP_JUNIT_REPORT_BASE_DIR).apply { mkdirs() }
    return createTempDirectory(baseDir.toPath(), "$prefix-").toFile()
}

private fun Element.intAttribute(name: String): Int? {
    return getAttribute(name).takeIf { it.isNotBlank() }?.toIntOrNull()
}

private fun Element.descendantElements(tagName: String): List<Element> {
    val nodes = getElementsByTagName(tagName)
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun Element.firstChildElement(tagName: String): Element? {
    return (0 until childNodes.length)
        .map { childNodes.item(it) }
        .firstOrNull { it.nodeType == Node.ELEMENT_NODE && it.nodeName == tagName } as? Element
}

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
