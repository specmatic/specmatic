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
    fun `sorts markdown and csv rows by display name`(@TempDir tempDir: Path) {
        val testResultsFile = tempDir.resolve("conformance-test-results.jsonl").toFile()
        val records = listOf(
            ConformanceTestRecord(
                status = ConformanceTestStatus.PASSED,
                tag = null,
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
                reason = "known gap",
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

        val markdownRows = markdownFile.readLines().filter { it.startsWith("| ") && !it.startsWith("| Status") && !it.startsWith("|---") }
        assertThat(markdownRows.map { it.split(" | ")[1] })
            .containsExactly("001-alpha", "002-beta")

        val csvRows = testResultsFile.resolveSibling("conformance-test-results.csv").readLines().drop(1)
        assertThat(csvRows.map { it.split("\",\"")[2] })
            .containsExactly("001-alpha", "002-beta")
    }
}
