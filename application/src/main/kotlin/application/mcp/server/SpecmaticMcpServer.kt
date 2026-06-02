package application.mcp.server

import application.mcp.server.tools.BackwardCompatArgs
import application.mcp.server.tools.BackwardCompatibilityTool
import application.mcp.server.tools.ContractTestTool
import application.mcp.server.tools.ManageMockServerArgs
import application.mcp.server.tools.MockServerTool
import application.mcp.server.tools.RunTestArgs
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.SystemExitException
import io.specmatic.specmatic.executable.VersionInfo
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration

class SpecmaticMcpServer(
    inputStream: InputStream = System.`in`,
    outputStream: OutputStream = System.out
) : AutoCloseable {
    private val objectMapper = jacksonObjectMapper()
    private val contractTestTool = ContractTestTool()
    private val mockServerTool = MockServerTool()
    private val backwardCompatibilityTool = BackwardCompatibilityTool()

    private val server: McpSyncServer = McpServer.sync(
        StdioServerTransportProvider(McpJsonDefaults.getMapper(), inputStream, outputStream)
    )
        .serverInfo("specmatic-mcp-server", VersionInfo.version)
        .capabilities(
            McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build()
        )
        .tools(tools())
        .build()

    private fun tools(): List<McpServerFeatures.SyncToolSpecification> {
        System.err.println("Registering Specmatic MCP tools...")

        return listOf(
            tool(
                name = "run_contract_test",
                description = "Run Specmatic contract tests against an API using OpenAPI specification",
                inputSchema = toolSchema(
                    properties = mapOf(
                        "openApiSpec" to stringProperty("The OpenAPI specification content (YAML or JSON)"),
                        "apiBaseUrl" to stringProperty("The base URL of the API to test against"),
                        "specFormat" to stringProperty("Format of the OpenAPI spec (yaml or json)")
                    ),
                    required = listOf("openApiSpec", "apiBaseUrl")
                )
            ) { request ->
                safeToolCall {
                    val args = objectMapper.convertValue<RunTestArgs>(request.arguments() ?: emptyMap<String, Any>())
                    contractTestTool.runContractTest(args, resiliency = false)
                }
            },
            tool(
                name = "run_resiliency_test",
                description = "Run Specmatic resiliency tests against an API using OpenAPI specification",
                inputSchema = toolSchema(
                    properties = mapOf(
                        "openApiSpec" to stringProperty("The OpenAPI specification content (YAML or JSON)"),
                        "apiBaseUrl" to stringProperty("The base URL of the API to test against"),
                        "specFormat" to stringProperty("Format of the OpenAPI spec (yaml or json)")
                    ),
                    required = listOf("openApiSpec", "apiBaseUrl")
                )
            ) { request ->
                safeToolCall {
                    val args = objectMapper.convertValue<RunTestArgs>(request.arguments() ?: emptyMap<String, Any>())
                    contractTestTool.runContractTest(args, resiliency = true)
                }
            },
            tool(
                name = "manage_mock_server",
                description = "Manage Specmatic mock servers: start, stop, or list running servers",
                inputSchema = toolSchema(
                    properties = mapOf(
                        "command" to stringProperty("The action to perform: start, stop, or list"),
                        "openApiSpec" to stringProperty("The OpenAPI specification content (required for 'start')"),
                        "port" to typedProperty("integer", "Port number for the mock server"),
                        "specFormat" to stringProperty("Format of the OpenAPI spec (yaml or json)")
                    ),
                    required = listOf("command")
                )
            ) { request ->
                safeToolCall {
                    val args = objectMapper.convertValue<ManageMockServerArgs>(request.arguments() ?: emptyMap<String, Any>())
                    mockServerTool.manageMockServer(args)
                }
            },
            tool(
                name = "backward_compatibility_check",
                description = "Check for breaking changes in OpenAPI specifications",
                inputSchema = toolSchema(
                    properties = mapOf(
                        "targetPath" to stringProperty("File or folder path to analyze"),
                        "baseBranch" to stringProperty("Git branch to compare against"),
                        "repoDir" to stringProperty("Repository directory")
                    ),
                    required = emptyList()
                )
            ) { request ->
                safeToolCall {
                    val args = objectMapper.convertValue<BackwardCompatArgs>(request.arguments() ?: emptyMap<String, Any>())
                    backwardCompatibilityTool.runBackwardCompatibilityCheck(args)
                }
            }
        ).also {
            System.err.println("Successfully registered tools.")
        }
    }

    fun run() {
        val stdioThread = waitForStdioThread()
        try {
            stdioThread?.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitForStdioThread(timeout: Duration = Duration.ofSeconds(5)): Thread? {
        val deadline = System.nanoTime() + timeout.toNanos()

        while (System.nanoTime() < deadline) {
            findStdioThread()?.let { return it }

            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }

        return findStdioThread()
    }

    private fun findStdioThread(): Thread? {
        return Thread.getAllStackTraces().entries.firstOrNull { (thread, stackTrace) ->
            !thread.isDaemon &&
                thread != Thread.currentThread() &&
                stackTrace.any { it.className.startsWith("io.modelcontextprotocol.server.transport.StdioServerTransportProvider") }
        }?.key
    }

    override fun close() {
        server.close()
    }

    private fun safeToolCall(block: () -> String): McpSchema.CallToolResult {
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

    private fun tool(
        name: String,
        description: String,
        inputSchema: McpSchema.JsonSchema,
        callHandler: (McpSchema.CallToolRequest) -> McpSchema.CallToolResult
    ): McpServerFeatures.SyncToolSpecification {
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(
                McpSchema.Tool.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build()
            )
            .callHandler { _, request -> callHandler(request) }
            .build()
    }

    private fun toolSchema(
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

    private fun stringProperty(description: String): Map<String, String> = typedProperty("string", description)

    private fun typedProperty(type: String, description: String): Map<String, String> =
        mapOf("type" to type, "description" to description)
}
