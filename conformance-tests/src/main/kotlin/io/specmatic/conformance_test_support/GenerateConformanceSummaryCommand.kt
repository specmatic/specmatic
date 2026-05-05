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

private const val DEFAULT_TEST_RESULTS_FILE = "build/conformance-test-results.jsonl"
private const val TEST_RESULTS_FILE_PROPERTY = "conformanceTestResultsJsonlFile"

private val jsonMapper = ObjectMapper().registerKotlinModule()

enum class ConformanceTestStatus {
    PASSED,
    EXPECTED_FAILURE,
    UNEXPECTED_FAILURE,
    UNEXPECTED_PASS,
}

data class ConformanceTestRecord(
    val status: ConformanceTestStatus,
    val tag: String?,
    val displayName: String,
    val testClass: String,
    val testMethod: String,
    val reason: String? = null,
    val specRef: String? = null,
    val failureMessage: String? = null,
)

@Command(
    name = "generateConformanceSummary",
    mixinStandardHelpOptions = true,
    description = ["Generate markdown summary of conformance test results"]
)
class GenerateConformanceSummaryCommand : Callable<Int> {
    @Option(
        names = ["--test-results-jsonl-file"],
        description = [
            "JSONL file written by ConformanceTestResultExtension. " +
                "Defaults to system property $TEST_RESULTS_FILE_PROPERTY, else $DEFAULT_TEST_RESULTS_FILE"
        ]
    )
    var testResultsJsonlFile: String = System.getProperty(TEST_RESULTS_FILE_PROPERTY) ?: DEFAULT_TEST_RESULTS_FILE

    @Option(
        names = ["--output-file"],
        description = ["Append the summary to this file (default: stdout)"]
    )
    var outputFile: String? = null

    override fun call(): Int {
        val testResultsFile = File(testResultsJsonlFile)
        val records = readRecords(testResultsFile)
            .sortedWith(compareBy({ it.displayName }, { it.testClass }, { it.testMethod }, { it.status.ordinal }))

        writeCsv(records, csvFileFor(testResultsFile))
        val markdown = renderMarkdown(records)

        if (outputFile != null) {
            File(outputFile!!).appendText(markdown)
        } else {
            print(markdown)
        }
        return 0
    }

    private fun readRecords(file: File): List<ConformanceTestRecord> {
        if (!file.isFile) {
            System.err.println("Test results file not found: ${file.path} (treating as zero records)")
            return emptyList()
        }

        return file.useLines { lines ->
            lines.mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) null else jsonMapper.readValue<ConformanceTestRecord>(trimmed)
            }.toList()
        }
    }

    private fun writeCsv(records: List<ConformanceTestRecord>, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(renderCsv(records))
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(GenerateConformanceSummaryCommand()).execute(*args))
}

private fun renderMarkdown(records: List<ConformanceTestRecord>): String = buildString {
    val byStatus = records.groupBy { it.status }
    val total = records.size
    val passed = byStatus[ConformanceTestStatus.PASSED]?.size ?: 0
    val expectedFailures = byStatus[ConformanceTestStatus.EXPECTED_FAILURE]?.size ?: 0
    val unexpectedFailures = byStatus[ConformanceTestStatus.UNEXPECTED_FAILURE]?.size ?: 0
    val unexpectedPasses = byStatus[ConformanceTestStatus.UNEXPECTED_PASS]?.size ?: 0

    appendLine("### Conformance Test Summary")
    appendLine()
    appendLine("**Test Statistics**")
    appendLine("- Total Tests: $total")
    appendLine("- Passed: $passed")
    appendLine("- Expected Failures: $expectedFailures")
    appendLine("- Unexpected Failures: $unexpectedFailures")
    appendLine("- Unexpected Passes: $unexpectedPasses")
    appendLine()

    appendLine("| Status | Display Name | Test Class | Test Method | Spec Reference | Notes |")
    appendLine("|--------|--------------|------------|-------------|----------------|-------|")
    for (record in records) {
        val simpleClass = record.testClass.substringAfterLast('.')
        val notes = when (record.status) {
            ConformanceTestStatus.EXPECTED_FAILURE,
            ConformanceTestStatus.UNEXPECTED_PASS -> record.reason.orEmpty()
            ConformanceTestStatus.UNEXPECTED_FAILURE -> record.failureMessage.orEmpty()
            ConformanceTestStatus.PASSED -> ""
        }
        appendLine(
            "| ${record.status.name} | ${escapeCell(record.displayName)} | ${escapeCell(simpleClass)} | " +
                "${escapeCell(record.testMethod)} | ${escapeCell(record.specRef.orEmpty())} | " +
                "${escapeCell(notes)} |"
        )
    }
    appendLine()
}

private fun csvFileFor(testResultsFile: File): File =
    testResultsFile.resolveSibling("${testResultsFile.nameWithoutExtension}.csv")

private fun renderCsv(records: List<ConformanceTestRecord>): String = buildString {
    appendCsvRow(
        listOf(
            "status",
            "tag",
            "displayName",
            "testClass",
            "testMethod",
            "specRef",
            "reason",
            "failureMessage",
        )
    )
    records.forEach { record ->
        appendCsvRow(
            listOf(
                record.status.name,
                record.tag.orEmpty(),
                record.displayName,
                record.testClass,
                record.testMethod,
                record.specRef.orEmpty(),
                record.reason.orEmpty(),
                record.failureMessage.orEmpty(),
            )
        )
    }
}

private fun StringBuilder.appendCsvRow(values: List<String>) {
    appendLine(values.joinToString(",") { it.toCsvCell() })
}

private fun String.toCsvCell(): String =
    "\"${replace("\"", "\"\"")}\""

private fun escapeCell(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\r\n", " ")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("|", "\\|")
