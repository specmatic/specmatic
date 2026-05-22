package application.mcp.server

import application.mcp.server.tools.*
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.io.*
import java.io.InputStream
import java.io.OutputStream

class SpecmaticMcpServer : AutoCloseable {
    private val contractTestTool = ContractTestTool()
    private val mockServerTool = MockServerTool()
    private val backwardCompatibilityTool = BackwardCompatibilityTool()

    private val server = Server(
        Implementation(
            name = "specmatic-mcp-server",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    init {
        registerTools()
    }

    private fun registerTools() {
        System.err.println("Registering Specmatic MCP tools...")
        
        server.addTool(
            name = "run_contract_test",
            description = "Run Specmatic contract tests against an API using OpenAPI specification",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("openApiSpec") {
                        put("type", "string")
                        put("description", "The OpenAPI specification content (YAML or JSON)")
                    }
                    putJsonObject("apiBaseUrl") {
                        put("type", "string")
                        put("description", "The base URL of the API to test against")
                    }
                    putJsonObject("specFormat") {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec (yaml or json)")
                    }
                },
                required = listOf("openApiSpec", "apiBaseUrl")
            )
        ) { request ->
            safeToolCall {
                val args = Json.decodeFromJsonElement<RunTestArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                contractTestTool.runContractTest(args, resiliency = false)
            }
        }

        server.addTool(
            name = "run_resiliency_test",
            description = "Run Specmatic resiliency tests against an API using OpenAPI specification",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("openApiSpec") {
                        put("type", "string")
                        put("description", "The OpenAPI specification content (YAML or JSON)")
                    }
                    putJsonObject("apiBaseUrl") {
                        put("type", "string")
                        put("description", "The base URL of the API to test against")
                    }
                    putJsonObject("specFormat") {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec (yaml or json)")
                    }
                },
                required = listOf("openApiSpec", "apiBaseUrl")
            )
        ) { request ->
            safeToolCall {
                val args = Json.decodeFromJsonElement<RunTestArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                contractTestTool.runContractTest(args, resiliency = true)
            }
        }

        server.addTool(
            name = "manage_mock_server",
            description = "Manage Specmatic mock servers: start, stop, or list running servers",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "The action to perform: start, stop, or list")
                    }
                    putJsonObject("openApiSpec") {
                        put("type", "string")
                        put("description", "The OpenAPI specification content (required for 'start')")
                    }
                    putJsonObject("port") {
                        put("type", "integer")
                        put("description", "Port number for the mock server")
                    }
                    putJsonObject("specFormat") {
                        put("type", "string")
                        put("description", "Format of the OpenAPI spec (yaml or json)")
                    }
                },
                required = listOf("command")
            )
        ) { request ->
            safeToolCall {
                val args = Json.decodeFromJsonElement<ManageMockServerArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                mockServerTool.manageMockServer(args)
            }
        }

        server.addTool(
            name = "backward_compatibility_check",
            description = "Check for breaking changes in OpenAPI specifications",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("targetPath") {
                        put("type", "string")
                        put("description", "File or folder path to analyze")
                    }
                    putJsonObject("baseBranch") {
                        put("type", "string")
                        put("description", "Git branch to compare against")
                    }
                    putJsonObject("repoDir") {
                        put("type", "string")
                        put("description", "Repository directory")
                    }
                },
                required = emptyList()
            )
        ) { request ->
            safeToolCall {
                val args = Json.decodeFromJsonElement<BackwardCompatArgs>(JsonObject(request.params.arguments ?: emptyMap()))
                backwardCompatibilityTool.runBackwardCompatibilityCheck(args)
            }
        }
        
        System.err.println("Successfully registered tools.")
    }

    suspend fun run(inputStream: InputStream = System.`in`, outputStream: OutputStream = System.`out`) {
        val transport = StdioServerTransport(inputStream.asSource().buffered(), outputStream.asSink().buffered())
        val session = server.createSession(transport)
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        session.onClose {
            done.complete(Unit)
        }
        done.await()
    }

    override fun close() {
        runBlocking {
            server.close()
        }
    }

    private fun safeToolCall(block: () -> String): CallToolResult {
        return try {
            val text = io.specmatic.core.utilities.SystemExit.throwOnExit {
                block()
            }
            CallToolResult(
                content = listOf(TextContent(text = text)),
                isError = false
            )
        } catch (t: Throwable) {
            val errorMessage = when (t) {
                is io.specmatic.core.utilities.SystemExitException -> t.message
                else -> t.message ?: (t::class.simpleName ?: "Unknown error")
            }

            t.printStackTrace(System.err)
            CallToolResult(
                content = listOf(
                    TextContent(
                        text = buildString {
                            append("# Specmatic MCP Tool Error\n\n")
                            append("- ")
                            append(errorMessage)
                        }
                    )
                ),
                isError = true
            )
        }
    }
}
