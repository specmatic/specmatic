package application.mcp.server

import application.mcp.server.tools.BackwardCompatArgs
import application.mcp.server.tools.BackwardCompatibilityTool
import application.mcp.server.tools.ContractTestTool
import application.mcp.server.tools.ManageMockServerArgs
import application.mcp.server.tools.MockServerTool
import application.mcp.server.tools.RunTestArgs
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpServerFeatures

class DefaultSpecmaticMcpToolProvider(
    private val contractTestTool: ContractTestTool = ContractTestTool(),
    private val mockServerTool: MockServerTool = MockServerTool(),
    private val backwardCompatibilityTool: BackwardCompatibilityTool = BackwardCompatibilityTool()
) : McpToolProvider {
    private val objectMapper = jacksonObjectMapper()

    override fun tools(): List<McpServerFeatures.SyncToolSpecification> {
        return listOf(
            getContractTestTool(),
            getResiliencyTestTool(),
            getMockServerTool(),
            getBCCTool()
        )
    }

    private fun getContractTestTool(): McpServerFeatures.SyncToolSpecification{
        return  tool(
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
        }
    }

    fun getMockServerTool(): McpServerFeatures.SyncToolSpecification {
        return tool(
            name = "manage_mock_server",
            description = "Manage Specmatic mock servers: start, stop, or list running servers using OpenAPI specification",
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
        }
    }

    fun getBCCTool(): McpServerFeatures.SyncToolSpecification {
        return tool(
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
    }

     fun getResiliencyTestTool(): McpServerFeatures.SyncToolSpecification {
       return tool(
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
        }
    }
}
