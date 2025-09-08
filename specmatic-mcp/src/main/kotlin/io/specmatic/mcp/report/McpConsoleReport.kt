package io.specmatic.mcp.report

import io.specmatic.core.log.logger
import io.specmatic.mcp.test.ScenarioExecutionResult
import io.specmatic.mcp.test.ToolStats

class McpConsoleReport(
    private val executionResults: Sequence<ScenarioExecutionResult>
) {
    fun generate() {
        logger.newLine()
        logger.log("=".repeat(80))
        logger.log("MCP AUTO TEST REPORT")
        logger.log("=".repeat(80))

        val noHeader = "Sr."
        val toolNameHeader = "Tool Name"
        val functionalHeader = "Passed"
        val brokenHeader = "Failed"
        val totalHeader = "Total"
        val successRateHeader = "Success %"

        // Group execution results by tool name and calculate stats
        val toolStats = executionResults
            .groupBy { it.toolName }
            .map { (toolName, results) ->
                val functional = results.count { it.verdict == "PASSED" }
                val broken = results.count { it.verdict == "FAILED" }
                val total = results.size
                val successRate = if (total > 0) (functional * 100.0 / total) else 0.0

                ToolStats(toolName, functional, broken, total, successRate)
            }
            .sortedBy { it.toolName }

        val maxToolNameLength = maxOf(toolNameHeader.length, toolStats.maxOfOrNull { it.toolName.length } ?: 0)
        val noWidth = 4
        val functionalWidth = 10
        val brokenWidth = 8
        val totalWidth = 6
        val successRateWidth = 10

        val headerSeparator = "-".repeat(noWidth + maxToolNameLength + functionalWidth + brokenWidth + totalWidth + successRateWidth + 17)

        logger.log(headerSeparator)
        logger.log("| ${noHeader.padEnd(noWidth)} | ${toolNameHeader.padEnd(maxToolNameLength)} | ${functionalHeader.padEnd(functionalWidth)} | ${brokenHeader.padEnd(brokenWidth)} | ${totalHeader.padEnd(totalWidth)} | ${successRateHeader.padEnd(successRateWidth)} |")
        logger.log(headerSeparator)

        // Table rows
        toolStats.forEachIndexed { index, stats ->
            val serialNo = (index + 1).toString()
            val successRateFormatted = String.format("%.1f%%", stats.successRate)

            logger.log("| ${serialNo.padEnd(noWidth)} | ${stats.toolName.padEnd(maxToolNameLength)} | ${stats.functional.toString().padEnd(functionalWidth)} | ${stats.broken.toString().padEnd(brokenWidth)} | ${stats.total.toString().padEnd(totalWidth)} | ${successRateFormatted.padEnd(successRateWidth)} |")
        }

        logger.log(headerSeparator)
        printSummary()
    }

    private fun printSummary() {
        val totalScenarios = executionResults.toList().size
        val totalPassed = executionResults.count { it.verdict == "PASSED" }
        val totalFailed = executionResults.count { it.verdict == "FAILED" }
        val overallSuccessRate = if (totalScenarios > 0) (totalPassed * 100.0 / totalScenarios) else 0.0

        logger.newLine()
        logger.log("SUMMARY:")
        logger.log("Total: $totalScenarios")
        logger.log("Passed: $totalPassed")
        logger.log("Failed: $totalFailed")
        logger.log("Overall Success Rate: ${String.format("%.1f%%", overallSuccessRate)}")
    }
}
