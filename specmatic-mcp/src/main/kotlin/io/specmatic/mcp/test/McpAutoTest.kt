package io.specmatic.mcp.test

import io.specmatic.core.Dictionary
import io.specmatic.core.log.logger
import io.specmatic.mcp.report.McpConsoleReport
import io.specmatic.mcp.report.McpJsonReport
import io.specmatic.mcp.test.client.McpTestClient
import io.specmatic.mcp.test.client.use
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex

class McpAutoTest(
    private val baseUrl: String,
    private val transport: McpTransport,
    private val enableResiliency: Boolean = false,
    private val dictionaryFile: File? = null,
    private val bearerToken: String? = null,
    private val filterTools: Set<String> = emptySet(),
    private val onlyNegativeTests: Boolean = false
) {
    val client = McpTestClient.from(baseUrl, transport, bearerToken)

    suspend fun run() = client.use {
        logger.newLine()
        logger.newLine()
        val executionResults = mutableListOf<ScenarioExecutionResult>()

        loadScenarios()
            .withIndex()
            .map { (index, scenario) ->
                executeScenario(scenario, index)
            }
            .collect { result ->
                executionResults.add(result)
            }

        McpConsoleReport(executionResults.asSequence()).generate()
        McpJsonReport(executionResults).generate()
    }

    private suspend fun loadScenarios(): Flow<McpScenario> {
        val dictionary = when {
            dictionaryFile == null || dictionaryFile.exists().not() -> Dictionary.empty()
            else -> Dictionary.from(dictionaryFile)
        }
        logWithTag("Fetching tools to load scenarios..")
        val tools = client.tools()
        logWithTag("Tools fetched successfully. Found ${tools.size} tools. Loading and executing scenarios..")

        return tools.filter { tool ->
            tool.name in filterTools || filterTools.isEmpty()
        }.asFlow()
            .flatMapConcat { tool ->
                McpScenario.from(tool, client, enableResiliency, dictionary, onlyNegativeTests).asFlow()
            }
    }

    private suspend fun executeScenario(scenario: McpScenario, index: Int): ScenarioExecutionResult {
        logger.newLine()
        logger.log("${index.inc()}. ${scenario.name} >> ")
        val executionResult = scenario.execute()

        val result = executionResult.result

        if (!result.isSuccess()) {
            logWithTag("Test failed with errors: ${result.reportString()}")
        }
        val verdict = if (result.isSuccess()) "PASSED" else "FAILED"
        logger.log("<< [Specmatic MCP] Test Result: $verdict")
        return executionResult
    }
}

fun logWithTag(message: String) {
    logger.log("[Specmatic MCP] $message")
}

data class ToolStats(
    val toolName: String,
    val functional: Int,
    val broken: Int,
    val total: Int,
    val successRate: Double
)
