package io.specmatic.mcp.report

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.defaultReportDirPath
import io.specmatic.core.loadSpecmaticConfigOrNull
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
            val specmaticConfig = loadSpecmaticConfigOrNull()
            val reportDirPath = specmaticConfig?.getReportDirPath("mcp") ?: defaultReportDirPath
            val directory = File(reportBaseDirectory).resolve(reportDirPath.toString())
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
