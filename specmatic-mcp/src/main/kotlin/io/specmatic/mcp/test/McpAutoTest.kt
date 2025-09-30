package io.specmatic.mcp.test

import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.Constants.Companion.ARTIFACTS_PATH
import io.specmatic.core.Dictionary
import io.specmatic.core.examples.module.FAILURE_EXIT_CODE
import io.specmatic.core.examples.module.SUCCESS_EXIT_CODE
import io.specmatic.core.log.logger
import io.specmatic.mcp.report.McpConsoleReport
import io.specmatic.mcp.report.McpJsonReport
import io.specmatic.mcp.test.client.McpTestClient
import io.specmatic.mcp.test.client.model.Tool
import io.specmatic.mcp.test.client.use
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex

class McpAutoTest(
    baseUrl: String,
    transport: McpTransport,
    private val enableResiliency: Boolean = false,
    private val dictionaryFile: File? = null,
    bearerToken: String? = null,
    private val filterTools: Set<String> = emptySet(),
    private val skipTools: Set<String> = emptySet()
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
        return@use if (executionResults.none { it.result.isSuccess() }) FAILURE_EXIT_CODE else SUCCESS_EXIT_CODE
    }

    private fun saveToolsSchemaResponse(tools: List<Tool>) {
        try {
            val directory = File(".").resolve(ARTIFACTS_PATH)
            directory.mkdirs()
            val file = File(directory, "tools_schema.json")
            logWithTag("Saving tools schema to ${file.canonicalPath} ...")

            val reportJson = ObjectMapper().writeValueAsString(tools)
            file.writeText(reportJson)
        } catch (e: Throwable) {
            logWithTag("Failed to save the tools schema: ${e.message}")
        }
    }

    private suspend fun loadScenarios(): Flow<McpScenario> {
        val dictionary = when {
            dictionaryFile == null || dictionaryFile.exists().not() -> Dictionary.empty()
            else -> Dictionary.from(dictionaryFile)
        }
        logWithTag("Fetching tools to load scenarios..")
        val tools = client.tools()
        logWithTag("Tools fetched successfully. Found ${tools.size} tools. Loading and executing scenarios..")
        saveToolsSchemaResponse(tools)

        return tools.filter { tool ->
            tool.name in filterTools || filterTools.isEmpty()
        }.filter { tool ->
            tool.name !in skipTools
        }.asFlow()
            .flatMapConcat { tool ->
                McpScenario.from(tool, client, enableResiliency, dictionary).asFlow()
            }.filterNotNull()
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
