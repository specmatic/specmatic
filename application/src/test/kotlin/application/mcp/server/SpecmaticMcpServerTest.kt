package application.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.specmatic.core.utilities.SystemExitException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpecmaticMcpServerTest {

    private val server = SpecmaticMcpServer()

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `server should register all public MCP tools with expected schemas`() {
        val tools = registeredTools()

        assertThat(tools.keys).containsExactlyInAnyOrder(
            "run_contract_test",
            "run_resiliency_test",
            "manage_mock_server",
            "backward_compatibility_check"
        )

        assertThat(tools.getValue("run_contract_test").tool.inputSchema.required)
            .containsExactly("openApiSpec", "apiBaseUrl")
        assertThat(tools.getValue("run_resiliency_test").tool.inputSchema.required)
            .containsExactly("openApiSpec", "apiBaseUrl")
        assertThat(tools.getValue("manage_mock_server").tool.inputSchema.required)
            .containsExactly("command")
        assertThat(tools.getValue("backward_compatibility_check").tool.inputSchema.required)
            .isEmpty()
        assertThat(tools.getValue("manage_mock_server").tool.inputSchema.properties!!.getValue("port")
            .jsonObject.getValue("type").jsonPrimitive.content).isEqualTo("integer")
    }

    @Test
    fun `safeToolCall should return success when block returns a string`() {
        val result = invokeSafeToolCall("Hello World")
        
        assertThat(result.isError).isFalse()
        assertThat(result.content).hasSize(1)
        assertThat((result.content[0] as TextContent).text).isEqualTo("Hello World")
    }

    @Test
    fun `safeToolCall should catch SystemExitException and return error report`() {
        val result = invokeSafeToolCall {
            throw SystemExitException(1, "Exit called")
        }
        
        assertThat(result.isError).isTrue()
        assertThat((result.content[0] as TextContent).text).contains("## Specmatic MCP Tool Error")
        assertThat((result.content[0] as TextContent).text).contains("> **Error:** Exit called")
    }

    @Test
    fun `safeToolCall should catch general exception and return error report`() {
        val result = invokeSafeToolCall {
            throw RuntimeException("Something went wrong")
        }
        
        assertThat(result.isError).isTrue()
        assertThat((result.content[0] as TextContent).text).contains("## Specmatic MCP Tool Error")
        assertThat((result.content[0] as TextContent).text).contains("> **Error:** Something went wrong")
    }

    @Test
    fun `safeToolCall should handle exceptions with null message`() {
        val result = invokeSafeToolCall {
            throw NullPointerException()
        }
        
        assertThat(result.isError).isTrue()
        assertThat((result.content[0] as TextContent).text).contains("> **Error:** NullPointerException")
    }

    private fun invokeSafeToolCall(returnValue: String): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        return invokeSafeToolCall { returnValue }
    }

    private fun invokeSafeToolCall(block: () -> String): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
        val method = SpecmaticMcpServer::class.java.getDeclaredMethod("safeToolCall", Function0::class.java)
        method.isAccessible = true
        return method.invoke(server, block) as io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
    }

    private fun registeredTools() = underlyingServer().tools

    private fun underlyingServer(): Server {
        val field = SpecmaticMcpServer::class.java.getDeclaredField("server")
        field.isAccessible = true
        return field.get(server) as Server
    }
}
