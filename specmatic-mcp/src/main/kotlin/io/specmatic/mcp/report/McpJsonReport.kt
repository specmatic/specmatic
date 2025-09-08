package io.specmatic.mcp.report

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.mcp.test.ScenarioExecutionResult
import io.specmatic.mcp.test.logWithTag
import java.io.File

const val ARTIFACTS_PATH = "./build/reports/specmatic"
private const val JSON_REPORT_FILE_NAME = "test_report.json"

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

            val reportJson = ObjectMapper().writeValueAsString(executionResults)
            file.writeText(reportJson)
        } catch(e: Throwable) {
            logWithTag("Failed to save JSON test report: ${e.message}")
        }
    }
}
