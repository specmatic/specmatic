package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

private val TEST_COUNT_REGEX = Regex("""<testsuite\b[^>]*\btests="(\d+)"""")
private const val DEFAULT_TEST_REPORT_XML_DIR = "build/test-results/test"
private const val DEFAULT_EXPECTED_FAILURE_RECORDS_FILE = "build/conformance-expected-failures.jsonl"
private const val EXPECTED_FAILURES_FILE_PROPERTY = "expectedFailuresJsonlFile"

private val jsonMapper = ObjectMapper().registerKotlinModule()

data class ExpectedFailureRecord(
    val tag: String,
    val displayName: String,
    val testClass: String,
    val testMethod: String,
    val reason: String,
    val specRef: String? = null,
)

@Command(
    name = "generateSummaryOfExpectedFailures",
    mixinStandardHelpOptions = true,
    description = ["Generate markdown summary of expected conformance test failures"]
)
class GenerateSummaryOfExpectedFailuresCommand : Callable<Int> {
    @Option(
        names = ["--test-report-xml-dir"],
        description = [$$"Directory containing TEST-*.xml files (default: ${DEFAULT-VALUE})"]
    )
    var testReportXmlDir: String = DEFAULT_TEST_REPORT_XML_DIR

    @Option(
        names = ["--expected-failures-jsonl-file"],
        description = [
            "JSONL file written by ExpectedFailureExtension. " +
                "Defaults to system property $EXPECTED_FAILURES_FILE_PROPERTY, else $DEFAULT_EXPECTED_FAILURE_RECORDS_FILE"
        ]
    )
    var expectedFailuresJsonlFile: String = System.getProperty(EXPECTED_FAILURES_FILE_PROPERTY) ?: DEFAULT_EXPECTED_FAILURE_RECORDS_FILE

    @Option(
        names = ["--output-file"],
        description = ["Append the summary to this file (default: stdout)"]
    )
    var outputFile: String? = null

    override fun call(): Int {
        val dir = File(testReportXmlDir)
        if (!dir.isDirectory) {
            System.err.println("XML directory not found: $testReportXmlDir")
            return 1
        }

        val totalTests = dir.listFiles { f -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
            ?.sumOf { TEST_COUNT_REGEX.find(it.readText())?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            ?: 0

        val records = readExpectedFailureRecords(File(expectedFailuresJsonlFile))
            .sortedWith(compareBy({ it.displayName }, { it.testClass }, { it.testMethod }))

        val markdown = renderMarkdown(records, totalTests)

        if (outputFile != null) {
            File(outputFile!!).appendText(markdown)
        } else {
            print(markdown)
        }
        return 0
    }

    private fun readExpectedFailureRecords(file: File): List<ExpectedFailureRecord> {
        if (!file.isFile) {
            System.err.println("Expected failures records file not found: ${file.path} (treating as zero records)")
            return emptyList()
        }

        return file.useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) null else jsonMapper.readValue<ExpectedFailureRecord>(trimmed)
            }.toList()
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(GenerateSummaryOfExpectedFailuresCommand()).execute(*args))
}

private fun renderMarkdown(records: List<ExpectedFailureRecord>, totalTests: Int): String = buildString {
    appendLine("### Expected Failures")
    appendLine()
    appendLine("| Display Name | Test Class | Test Method | Reason | Spec Reference |")
    appendLine("|--------------|------------|-------------|--------|----------------|")
    for (record in records) {
        val simpleClass = record.testClass.substringAfterLast('.')
        appendLine(
            "| ${escapeCell(record.displayName)} | ${escapeCell(simpleClass)} | " +
                "${escapeCell(record.testMethod)} | ${escapeCell(record.reason)} | " +
                "${escapeCell(record.specRef.orEmpty())} |"
        )
    }
    appendLine()
    appendLine("**Test Statistics**")
    appendLine("- Total Tests: $totalTests")
    appendLine("- Expected Failures: ${records.size}")
    appendLine()
}

private fun escapeCell(value: String): String =
    value.replace("\\", "\\\\").replace("|", "\\|")
