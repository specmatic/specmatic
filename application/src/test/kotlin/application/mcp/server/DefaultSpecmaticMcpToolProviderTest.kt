package application.mcp.server

import application.mcp.server.tools.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultSpecmaticMcpToolProviderTest {
    private val contractTestTool = mockk<ContractTestTool>()
    private val mockServerTool = mockk<MockServerTool>()
    private val backwardCompatibilityTool = mockk<BackwardCompatibilityTool>()
    private val provider = DefaultSpecmaticMcpToolProvider(contractTestTool, mockServerTool, backwardCompatibilityTool)

    @Test
    fun `should register all expected tools`() {
        val tools = provider.tools()
        val toolNames = tools.map { it.tool().name() }

        assertThat(toolNames).containsExactlyInAnyOrder(
            "run_contract_test",
            "run_resiliency_test",
            "manage_mock_server",
            "backward_compatibility_check"
        )
    }

    @Test
    fun `run_contract_test tool should call contractTestTool with resiliency false`() {
        val tools = provider.tools()
        val tool = tools.first { it.tool().name() == "run_contract_test" }
        
        val args = mapOf(
            "openApiSpec" to "openapi: 3.0.0",
            "apiBaseUrl" to "http://localhost:8080",
            "specFormat" to "yaml"
        )
        val request = McpSchema.CallToolRequest("run_contract_test", args)

        every { contractTestTool.runContractTest(any(), resiliency = false) } returns "Contract Test Passed"

        val result = tool.callHandler().apply(null, request)

        assertThat(result.isError).isFalse()
        assertThat(result.content()).hasSize(1)
        assertThat((result.content()[0] as McpSchema.TextContent).text()).isEqualTo("Contract Test Passed")
        
        verify { 
            contractTestTool.runContractTest(
                RunTestArgs(openApiSpec = "openapi: 3.0.0", apiBaseUrl = "http://localhost:8080", specFormat = "yaml"),
                resiliency = false
            ) 
        }
    }

    @Test
    fun `run_resiliency_test tool should call contractTestTool with resiliency true`() {
        val tools = provider.tools()
        val tool = tools.first { it.tool().name() == "run_resiliency_test" }
        
        val args = mapOf(
            "openApiSpec" to "openapi: 3.0.0",
            "apiBaseUrl" to "http://localhost:8080"
        )
        val request = McpSchema.CallToolRequest("run_resiliency_test", args)

        every { contractTestTool.runContractTest(any(), resiliency = true) } returns "Resiliency Test Passed"

        val result = tool.callHandler().apply(null, request)

        assertThat(result.isError).isFalse()
        assertThat((result.content()[0] as McpSchema.TextContent).text()).isEqualTo("Resiliency Test Passed")
        
        verify { 
            contractTestTool.runContractTest(
                RunTestArgs(openApiSpec = "openapi: 3.0.0", apiBaseUrl = "http://localhost:8080"),
                resiliency = true
            ) 
        }
    }

    @Test
    fun `manage_mock_server tool should call mockServerTool`() {
        val tools = provider.tools()
        val tool = tools.first { it.tool().name() == "manage_mock_server" }
        
        val args = mapOf(
            "command" to "start",
            "openApiSpec" to "openapi: 3.0.0",
            "port" to 9000
        )
        val request = McpSchema.CallToolRequest("manage_mock_server", args)

        every { mockServerTool.manageMockServer(any()) } returns "Mock Server Started"

        val result = tool.callHandler().apply(null, request)

        assertThat(result.isError).isFalse()
        assertThat((result.content()[0] as McpSchema.TextContent).text()).isEqualTo("Mock Server Started")
        
        verify { 
            mockServerTool.manageMockServer(
                ManageMockServerArgs(command = "start", openApiSpec = "openapi: 3.0.0", port = 9000)
            ) 
        }
    }

    @Test
    fun `backward_compatibility_check tool should call backwardCompatibilityTool`() {
        val tools = provider.tools()
        val tool = tools.first { it.tool().name() == "backward_compatibility_check" }
        
        val args = mapOf(
            "targetPath" to "spec.yaml",
            "baseBranch" to "main"
        )
        val request = McpSchema.CallToolRequest("backward_compatibility_check", args)

        every { backwardCompatibilityTool.runBackwardCompatibilityCheck(any()) } returns "No breaking changes"

        val result = tool.callHandler().apply(null, request)

        assertThat(result.isError).isFalse()
        assertThat((result.content()[0] as McpSchema.TextContent).text()).isEqualTo("No breaking changes")
        
        verify { 
            backwardCompatibilityTool.runBackwardCompatibilityCheck(
                BackwardCompatArgs(targetPath = "spec.yaml", baseBranch = "main")
            ) 
        }
    }

    @Test
    fun `should handle missing optional arguments correctly`() {
        val tools = provider.tools()
        val tool = tools.first { it.tool().name() == "run_contract_test" }
        
        val args = mapOf(
            "openApiSpec" to "openapi: 3.0.0",
            "apiBaseUrl" to "http://localhost:8080"
        )
        val request = McpSchema.CallToolRequest("run_contract_test", args)

        every { contractTestTool.runContractTest(any(), any()) } returns "Success"

        tool.callHandler().apply(null, request)

        verify { 
            contractTestTool.runContractTest(
                RunTestArgs(openApiSpec = "openapi: 3.0.0", apiBaseUrl = "http://localhost:8080", specFormat = "yaml"),
                resiliency = false
            ) 
        }
    }
}
