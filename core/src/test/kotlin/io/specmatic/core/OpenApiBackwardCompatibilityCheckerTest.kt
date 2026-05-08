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

        val result = OpenApiBackwardCompatibilityChecker(v1, v2).run(tempDir)

        println(result.values.fold(Results()) { acc, results -> acc.plus(results) }.report())

        val htmlReport = File(tempDir, "html/index.html")
        assertThat(htmlReport).exists().isFile()

        val report = extractEmbeddedReport(htmlReport)
        val summary = report["results"]["summary"]
        assertThat(summary["failed"].asInt()).isPositive()
        assertThat(summary["tests"].asInt()).isEqualTo(summary["passed"].asInt() + summary["failed"].asInt())

        val tests = report["results"]["tests"].toList()
        val failingTests = tests.filter { it["status"].asText() == "failed" }
        val failingTestNames = failingTests.map { it["name"].asText() }
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

        val missingTest = failingTests.first { it["name"].asText().contains("/missing") }
        assertThat(missingTest["message"].asText())
            .contains("This API exists in the old contract but not in the new contract")

        val existsTest = failingTests.first { it["name"].asText().contains("/exists/{id}") }
        val existsMessage = existsTest["message"].asText()
        assertThat(existsMessage).contains("REQUEST.BODY.field", "RESPONSE.BODY.field")
        assertThat(existsMessage).contains("R1001", "Type mismatch")
        assertThat(existsMessage).contains("number", "string")
    }

    @Test
    fun `should mark only the breaking GET operation as failed when sibling POST stays compatible`(@TempDir tempDir: File) {
        val v1 = OpenApiSpecification.fromFile("src/test/resources/openapi/products_get_break/openapi_v1.yaml").toFeature()
        val v2 = OpenApiSpecification.fromFile("src/test/resources/openapi/products_get_break/openapi_v2.yaml").toFeature()

        OpenApiBackwardCompatibilityChecker(v1, v2).run(tempDir)

        val report = extractEmbeddedReport(File(tempDir, "html/index.html"))
        val tests = report["results"]["tests"].toList()

        val getTest = tests.single { it["name"].asText().contains("GET /products/{id}") }
        assertThat(getTest["status"].asText()).isEqualTo("failed")
        val getMessage = getTest["message"].asText()
        assertThat(getMessage).contains("RESPONSE.BODY.name")
        assertThat(getMessage).contains("R1001", "Type mismatch")
        assertThat(getMessage).contains("number", "string")

        val postTest = tests.single { it["name"].asText().contains("POST /products/{id}") }
        assertThat(postTest["status"].asText()).isEqualTo("passed")
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
