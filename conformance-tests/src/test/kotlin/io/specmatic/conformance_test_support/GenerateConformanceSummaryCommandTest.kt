package io.specmatic.conformance_test_support

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path

class GenerateConformanceSummaryCommandTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `writes sorted markdown and csv rows with report columns`(@TempDir tempDir: Path) {
        val testResultsFile = tempDir.resolve("conformance-test-results.jsonl").toFile()
        val records = listOf(
            ConformanceTestRecord(
                status = ConformanceTestStatus.PASSED,
                tag = "x-specmatic-expect-failure-loop",
                displayName = "002-beta",
                testClass = "io.specmatic.conformance_tests.S002BetaTest",
                testMethod = "passes()",
                specRef = "Beta spec",
            ),
            ConformanceTestRecord(
                status = ConformanceTestStatus.EXPECTED_FAILURE,
                tag = "x-specmatic-expect-failure-request-bodies",
                displayName = "001-alpha",
                testClass = "io.specmatic.conformance_tests.S001AlphaTest",
                testMethod = "fails as expected()",
                failureReason = "known gap",
                specRef = "Alpha spec",
            ),
        )
        testResultsFile.writeText(records.joinToString("\n") { objectMapper.writeValueAsString(it) })
        val markdownFile = tempDir.resolve("summary.md").toFile()

        val exitCode = CommandLine(GenerateConformanceSummaryCommand()).execute(
            "--test-results-jsonl-file",
            testResultsFile.path,
            "--output-file",
            markdownFile.path,
        )

        assertThat(exitCode).isEqualTo(0)

        val markdownLines = markdownFile.readLines()
        assertThat(markdownLines).contains("| Display Name | Status | Test Method | Spec Reference | Failure Reason |")
        assertThat(markdownLines).doesNotContain("| Status | Display Name | Test Class | Test Method | Spec Reference | Notes |")
        val markdownRows = markdownLines.filter {
            it.startsWith("| ") && !it.startsWith("| Display Name") && !it.startsWith("|---")
        }
        assertThat(markdownRows.map { it.markdownCell(1) })
            .containsExactly("001-alpha", "002-beta")
        assertThat(markdownRows.map { it.markdownCell(2) })
            .containsExactly("EXPECTED_FAILURE", "PASSED")
        assertThat(markdownRows.map { it.markdownCell(5) })
            .containsExactly("known gap", "")
        assertThat(markdownFile.readText()).doesNotContain("x-specmatic-expect-failure")

        val csvLines = testResultsFile.resolveSibling("conformance-test-results.csv").readLines()
        assertThat(csvLines.first()).isEqualTo("\"displayName\",\"status\",\"testMethod\",\"specRef\",\"failureReason\"")
        val csvRows = csvLines.drop(1)
        assertThat(csvRows.map { it.split("\",\"")[0].removePrefix("\"") })
            .containsExactly("001-alpha", "002-beta")
        assertThat(csvRows.map { it.split("\",\"")[1] })
            .containsExactly("EXPECTED_FAILURE", "PASSED")
        assertThat(csvRows.map { it.split("\",\"")[4].removeSuffix("\"") })
            .containsExactly("known gap", "")
        assertThat(csvLines.joinToString("\n")).doesNotContain("x-specmatic-expect-failure")
    }

    private fun String.markdownCell(index: Int): String =
        split("|")[index].trim()
}
