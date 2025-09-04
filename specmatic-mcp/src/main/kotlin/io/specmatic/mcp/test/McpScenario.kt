package io.specmatic.mcp.test

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.core.Dictionary
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.pattern.singleLineDescription
import io.specmatic.mcp.parser.JsonSchemaToPattern
import io.specmatic.mcp.test.client.McpTestClient
import io.specmatic.mcp.test.client.model.JsonRpcResponse
import io.specmatic.mcp.test.client.model.Tool

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolResponse(
    @JsonProperty("structuredContent")
    val structuredContent: Map<String, Any>? = emptyMap(),
    @JsonProperty("content")
    val content: Any,
    @JsonProperty("isError")
    val isError: Boolean
)

data class ScenarioExecutionResult(
    val name: String,
    val toolName: String,
    val isNegative: Boolean,
    val request: Map<String, Any>,
    val response: JsonRpcResponse,
    @JsonIgnore
    val result: Result,
    val verdict: String = if(result.isSuccess()) "PASSED" else "FAILED",
    val error: String? = if(result.isSuccess()) null else result.reportString()
)

data class McpScenario(
    val name: String,
    val toolName: String,
    val inputPattern: JSONObjectPattern,
    val outputPattern: JSONObjectPattern? = null,
    val mcpTestClient: McpTestClient,
    private val resolver: Resolver = Resolver(),
    private val isNegative: Boolean = false
) {

    suspend fun execute(): ScenarioExecutionResult {
        val arguments = ObjectMapper().readValue(
            inputPattern.generate(resolver).toStringLiteral(),
            object : TypeReference<Map<String, Any>>() {},
        )

        val response = try {
            mcpTestClient.toolCall(
                toolName = toolName,
                arguments = arguments,
            )
        } catch(e: Throwable) {
            return ScenarioExecutionResult(
                name = name,
                toolName = toolName,
                isNegative = isNegative,
                request = arguments,
                response = JsonRpcResponse("2.0", null),
                result = Result.Failure("Tool invocation failed: ${e.message}"),
            )
        }

        if (response.error != null && response.error.isInternalErrorStatusCode()) {
            return ScenarioExecutionResult(
                name = name,
                toolName = toolName,
                isNegative = isNegative,
                request = arguments,
                response = response,
                result = Result.Failure("Tool invocation failed with an internal server error: ${response.error.message}"),
            )
        }

        val result = when {
            isNegative -> executionResultForNegativeScenario(response)
            else -> executionResultForPositiveScenario(response)
        }

        return ScenarioExecutionResult(
            name = name,
            toolName = toolName,
            isNegative = isNegative,
            request = arguments,
            response = response,
            result = result
        )
    }

    private fun executionResultForNegativeScenario(response: JsonRpcResponse): Result {
        if (response.error != null) {
            if(response.error.isInvalidParamsStatusCode()) return Result.Success()
            return Result.Failure("Expected an error with code -32602 but got ${response.error?.code ?: "no error"}")
        }

        val toolResponse = try {
            ObjectMapper().treeToValue(response.result, ToolResponse::class.java)
        } catch (e: Throwable) {
            return Result.Failure(e.message ?: "Unable to fetch a valid response from the tool")
        }

        if (toolResponse.isError) return Result.Success()

        return Result.Failure("Expected an error response but got a successful response: ${toolResponse.content}")
    }

    private fun executionResultForPositiveScenario(response: JsonRpcResponse): Result {
        if (response.error != null) {
            if (response.error.isInvalidParamsStatusCode()) return Result.Failure(response.error.message)
            logWithTag("JSON RPC error received, but the passed arguments are accepted since the status code is not -32602; hence, the test has passed.")
            return Result.Success()
        }
        val toolResponse = try {
            ObjectMapper().treeToValue(response.result, ToolResponse::class.java)
        } catch (e: Throwable) {
            return Result.Failure(e.message ?: "Unable to fetch a valid response from the tool")
        }

        if (toolResponse.isError) {
            return Result.Failure(toolResponse.content.toString())
        }
        if (outputPattern == null) return Result.Success()

        return outputPattern.matches(
            parsedValue(ObjectMapper().writeValueAsString(toolResponse.structuredContent)),
            resolver,
        )
    }

    private fun newBasedOn(): Sequence<McpScenario> {
        return inputPattern.newBasedOn(Row(), resolver).mapNotNull { newInputPattern ->
            scenarioWith(newInputPattern)
        }
    }

    private fun negativeBasedOn(): Sequence<McpScenario> {
        return inputPattern.negativeBasedOn(
            Row(), resolver,
        ).mapNotNull { newInputPattern -> scenarioWith(newInputPattern, isNegative = true) }
    }

    private fun scenarioWith(newInputPattern: ReturnValue<Pattern>, isNegative: Boolean = false): McpScenario? {
        if (newInputPattern !is HasValue<*>) return null
        try {
            newInputPattern as HasValue<JSONObjectPattern>
            val details = newInputPattern.valueDetails.singleLineDescription()
            if (isNegative && details.isBlank()) return null
            val detailedName = buildString {
                append(if (isNegative) "(-ve) " else "(+ve) ")
                append("Tool '$toolName'")
                if (details.isNotBlank()) append("| Mutation: [$details]")
            }
            return this.copy(
                name = detailedName,
                inputPattern = newInputPattern.value,
                isNegative = isNegative,
                resolver = if (isNegative) resolver.copy(dictionary = Dictionary.empty()) else resolver
            )
        } catch (_: Throwable) {
            return null
        }
    }

    companion object {
        fun from(
            tool: Tool,
            mcpTestClient: McpTestClient,
            enableResiliency: Boolean,
            dictionary: Dictionary = Dictionary.empty(),
            onlyNegativeTests: Boolean
        ): Sequence<McpScenario> {
            val inputPatternMap = JsonSchemaToPattern(tool.inputSchema).pattern()
            val outputPatternMap = tool.outputSchema?.let {
                JsonSchemaToPattern(it).pattern()
            }
            val scenario = McpScenario(
                name = tool.name,
                toolName = tool.name,
                inputPattern = JSONObjectPattern(inputPatternMap, typeAlias = tool.name),
                outputPattern = outputPatternMap?.let {
                    JSONObjectPattern(it, typeAlias = tool.name)
                },
                mcpTestClient = mcpTestClient,
                resolver = Resolver(dictionary = dictionary),
            )

            return when {
                enableResiliency -> scenario.newBasedOn() + scenario.negativeBasedOn()
                onlyNegativeTests -> scenario.negativeBasedOn()
                else -> sequenceOf(scenario)
            }
        }
    }
}
