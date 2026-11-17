package application.mcp.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.SystemExitException

private val schemaMapper = ObjectMapper().registerKotlinModule()

fun tool(
    name: String,
    description: String,
    inputSchema: McpSchema.JsonSchema,
    callHandler: (McpSchema.CallToolRequest) -> McpSchema.CallToolResult
): McpServerFeatures.SyncToolSpecification {
    val schema = schemaMapper.convertValue(inputSchema, Map::class.java) as Map<String, Any>
    val validator = McpJsonDefaults.getSchemaValidator()
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(
            McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build()
        )
        .callHandler { _, request ->
            val validation = validator.validate(schema, request.arguments().orEmpty())
            if (validation.valid()) {
                callHandler(request)
            } else {
                McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent("Invalid tool arguments: ${validation.errorMessage()}")
                    .build()
            }
        }
        .build()
}

fun toolSchema(
    properties: Map<String, Any>,
    required: List<String>
): McpSchema.JsonSchema {
    return McpSchema.JsonSchema(
        "object",
        properties,
        required,
        false,
        null,
        null
    )
}

fun stringProperty(description: String): Map<String, String> = typedProperty("string", description)

fun typedProperty(type: String, description: String): Map<String, String> =
    mapOf("type" to type, "description" to description)

fun safeToolCall(block: () -> String): McpSchema.CallToolResult {
    return try {
        val text = SystemExit.throwOnExit {
            block()
        }
        McpSchema.CallToolResult.builder()
            .addTextContent(text)
            .isError(false)
            .build()
    } catch (t: Throwable) {
        val errorMessage = when (t) {
            is SystemExitException -> t.message
            else -> t.message ?: (t::class.simpleName ?: "Unknown error")
        }

        t.printStackTrace(System.err)
        McpSchema.CallToolResult.builder()
            .addTextContent(
                buildString {
                    append("## Specmatic MCP Tool Error\n\n")
                    append("> **Error:** $errorMessage\n\n")
                    append("Please check the logs or ensure the inputs are correct.")
                }
            )
            .isError(true)
            .build()
    }
}
