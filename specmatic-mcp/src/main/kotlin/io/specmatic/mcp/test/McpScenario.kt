package io.specmatic.mcp.test

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Dictionary
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.HasValue
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.ReturnValue
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.pattern.singleLineDescription
import io.specmatic.core.utilities.jsonObjectMapper
import io.specmatic.mcp.constants.SchemaType
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
        val arguments = jsonObjectMapper.readValue(
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
            jsonObjectMapper.treeToValue(response.result, ToolResponse::class.java)
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
            jsonObjectMapper.treeToValue(response.result, ToolResponse::class.java)
        } catch (e: Throwable) {
            return Result.Failure(e.message ?: "Unable to fetch a valid response from the tool")
        }

        if (toolResponse.isError) {
            return Result.Failure(toolResponse.content.toString())
        }
        if (outputPattern == null) return Result.Success()

        return outputPattern.matches(
            parsedValue(jsonObjectMapper.writeValueAsString(toolResponse.structuredContent)),
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
                if (details.isNotBlank()) append(" where the 'arguments' body$details")
            }
            return this.copy(
                name = detailedName,
                inputPattern = newInputPattern.value,
                isNegative = isNegative,
                resolver = resolver.copy(isNegative = isNegative)
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
            dictionary: Dictionary = Dictionary.empty()
        ): Sequence<McpScenario?> {
            try {
                logger.disableInfoLogging()
                val inputPatterns =  OpenApiSpecification.patternsFrom(
                    jsonSchema = tool.inputSchema.toMap(),
                    schemaName = tool.name
                )
                val outputPatterns = tool.outputSchema?.let {
                    OpenApiSpecification.patternsFrom(
                        jsonSchema = it.toMap(),
                        schemaName = tool.name
                    )
                }
                logger.enableInfoLogging()

                val resolver = Resolver(
                    dictionary = dictionary,
                    newPatterns = inputPatterns.plus(outputPatterns.orEmpty())
                )

                val inputPattern = inputPatterns.getValue("(${tool.name})")
                    .returnIfJsonObjectPattern(tool.name, SchemaType.INPUT, resolver)
                    .copy(typeAlias = tool.name)
                val outputPattern = outputPatterns?.getValue("(${tool.name})")
                    ?.returnIfJsonObjectPattern(tool.name, SchemaType.OUTPUT, resolver)
                    ?.copy(typeAlias = tool.name)

                val scenario = McpScenario(
                    name = tool.name,
                    toolName = tool.name,
                    inputPattern = inputPattern,
                    outputPattern = outputPattern,
                    mcpTestClient = mcpTestClient,
                    resolver = resolver
                )

                return when {
                    enableResiliency -> scenario.newBasedOn() + scenario.negativeBasedOn()
                    else -> sequenceOf(scenario)
                }
            } catch(e: Throwable) {
                logger.enableInfoLogging()
                logWithTag("Skipping tool '${tool.name}' as failed to generate the scenario with following errors: ${e.message}")
                return sequenceOf(null)
            }
        }

        private fun Pattern.returnIfJsonObjectPattern(
            toolName: String,
            schemaType: SchemaType,
            resolver: Resolver
        ): JSONObjectPattern {
            val resolvedPattern = resolver.withCyclePrevention(this) { resolverWithCyclePrevention ->
                resolvedHop(this, resolverWithCyclePrevention)
            }
            if (resolvedPattern is JSONObjectPattern) return resolvedPattern
            throw IllegalArgumentException("Expected a JSONObjectPattern, but got ${pattern::class.simpleName} for ${schemaType.name.lowercase()} schema of tool '$toolName'")
        }
    }
}

fun JsonNode.toMap(): Map<String, Any> {
    return jsonObjectMapper.convertValue(
        this,
        object : TypeReference<Map<String, Any>>() {}
    )
}
