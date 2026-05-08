package io.specmatic.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conversions.OpenApiSpecification
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiBackwardCompatibilityCheckerTest {
    @Test
    fun `should produce a passing html report when the new spec is backward compatible with the old`(@TempDir tempDir: File) {
        val v1 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct/openapi_v1.yaml").toFeature()

        OpenApiBackwardCompatibilityChecker(v1, v1).run(tempDir)

        val htmlReport = File(tempDir, "html/index.html")
        assertThat(htmlReport).exists().isFile()

        val report = extractEmbeddedReport(htmlReport)
        val summary = report["results"]["summary"]
        assertThat(summary["failed"].asInt()).isEqualTo(0)
        assertThat(summary["passed"].asInt()).isPositive()
        assertThat(summary["tests"].asInt()).isEqualTo(summary["passed"].asInt())

        val tests = report["results"]["tests"].toList()
        assertThat(tests).isNotEmpty()
        assertThat(tests).allSatisfy { test ->
            assertThat(test["status"].asText()).isEqualTo("passed")
        }

        val testNames = tests.map { it["name"].asText() }
        assertThat(testNames).anyMatch { it.contains("/missing") }
        assertThat(testNames).anyMatch { it.contains("/exists/{id}") }
    }

    @Test
    fun `should produce a failing html report when the new spec breaks compatibility with the old`(@TempDir tempDir: File) {
        val v1 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct/openapi_v1.yaml").toFeature()
        val v2 = OpenApiSpecification.fromFile("src/test/resources/openapi/multi_req_res_ct/openapi_v2.yaml").toFeature()

        OpenApiBackwardCompatibilityChecker(v1, v2).run(tempDir)

        val htmlReport = File(tempDir, "html/index.html")
        assertThat(htmlReport).exists().isFile()

        val report = extractEmbeddedReport(htmlReport)
        val summary = report["results"]["summary"]
        assertThat(summary["failed"].asInt()).isPositive()
        assertThat(summary["tests"].asInt()).isEqualTo(summary["passed"].asInt() + summary["failed"].asInt())

        val tests = report["results"]["tests"].toList()
        val failingTestNames = tests
            .filter { it["status"].asText() == "failed" }
            .map { it["name"].asText() }
        assertThat(failingTestNames)
            .withFailMessage(
                "Expected at least one failing test for /missing (removed in v2). Failing tests: %s",
                failingTestNames
            )
            .anyMatch { it.contains("/missing") }
        assertThat(failingTestNames)
            .withFailMessage(
                "Expected at least one failing test for /exists/{id} (field type changed string -> integer in v2). Failing tests: %s",
                failingTestNames
            )
            .anyMatch { it.contains("/exists/{id}") }
    }

    private fun extractEmbeddedReport(htmlFile: File): JsonNode {
        val html = htmlFile.readText()
        val marker = "const report = "
        val start = html.indexOf(marker)
        check(start >= 0) { "Could not find embedded report JSON in $htmlFile" }
        val mapper = ObjectMapper()
        val parser = mapper.factory.createParser(html.substring(start + marker.length))
        return mapper.readTree(parser)
    }
}
