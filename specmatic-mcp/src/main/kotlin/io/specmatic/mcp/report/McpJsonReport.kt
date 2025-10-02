package io.specmatic.mcp.report

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.Constants.Companion.ARTIFACTS_PATH
import io.specmatic.core.utilities.jsonObjectMapper
import io.specmatic.mcp.test.ScenarioExecutionResult
import io.specmatic.mcp.test.logWithTag
import java.io.File

private const val JSON_REPORT_FILE_NAME = "mcp_test_report.json"

class McpJsonReport(
    private val executionResults: List<ScenarioExecutionResult>,
    private val reportBaseDirectory: String = ".",
) {
    fun generate() {
        try {
            val directory = File(reportBaseDirectory).resolve(ARTIFACTS_PATH)
            directory.mkdirs()
            val file = File(directory, JSON_REPORT_FILE_NAME)
            logWithTag("Saving JSON test report to ${file.canonicalPath} ...")

            val reportJson = jsonObjectMapper.writeValueAsString(executionResults)
            file.writeText(reportJson)
        } catch(e: Throwable) {
            logWithTag("Failed to save JSON test report: ${e.message}")
        }
    }
}
