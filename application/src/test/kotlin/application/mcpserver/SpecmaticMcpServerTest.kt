package application.mcpserver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpecmaticMcpServerTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `initialize returns MCP metadata`() {
        val server = SpecmaticMcpServer(toolExecutor = FakeToolExecutor())

        val response = server.handle(
            mapper.readTree(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """.trimIndent()
            )
        )

        assertThat(response).isNotNull
        assertThat(response!!.path("result").path("protocolVersion").asText()).isEqualTo("2024-11-05")
        assertThat(response.path("result").path("serverInfo").path("name").asText()).isEqualTo("specmatic-mcp-server")
    }

    @Test
    fun `initialized notification does not produce a response`() {
        val server = SpecmaticMcpServer(toolExecutor = FakeToolExecutor())

        val response = server.handle(
            mapper.readTree(
                """
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                """.trimIndent()
            )
        )

        assertThat(response).isNull()
    }

    @Test
    fun `tools list exposes specmatic tools`() {
        val server = SpecmaticMcpServer(toolExecutor = FakeToolExecutor())

        val response = server.handle(
            mapper.readTree(
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """.trimIndent()
            )
        )

        val toolNames = response!!.path("result").path("tools").map { it.path("name").asText() }
        assertThat(toolNames).containsExactly(
            "run_contract_test",
            "run_resiliency_test",
            "manage_mock_server",
            "backward_compatibility_check"
        )
    }

    @Test
    fun `tool call delegates to executor and wraps text result`() {
        val executor = FakeToolExecutor()
        val server = SpecmaticMcpServer(toolExecutor = executor)

        val response = server.handle(
            mapper.readTree(
                """
                {
                  "jsonrpc":"2.0",
                  "id":3,
                  "method":"tools/call",
                  "params":{
                    "name":"run_contract_test",
                    "arguments":{"openApiSpec":"openapi: 3.0.0","apiBaseUrl":"http://localhost:8080"}
                  }
                }
                """.trimIndent()
            )
        )

        assertThat(executor.lastTool).isEqualTo("run_contract_test")
        assertThat(response!!.path("result").path("content")[0].path("text").asText()).isEqualTo("contract result")
    }

    @Test
    fun `unknown tool returns method not found error`() {
        val server = SpecmaticMcpServer(toolExecutor = FakeToolExecutor())

        val response = server.handle(
            mapper.readTree(
                """
                {
                  "jsonrpc":"2.0",
                  "id":4,
                  "method":"tools/call",
                  "params":{"name":"missing_tool","arguments":{}}
                }
                """.trimIndent()
            )
        )

        assertThat(response).isNotNull
        assertThat(response!!.path("error").path("code").asInt()).isEqualTo(-32601)
    }

    private class FakeToolExecutor : SpecmaticMcpToolExecutor {
        var lastTool: String? = null

        override fun runContractTest(arguments: com.fasterxml.jackson.databind.JsonNode, resiliency: Boolean): String {
            lastTool = if (resiliency) "run_resiliency_test" else "run_contract_test"
            return if (resiliency) "resiliency result" else "contract result"
        }

        override fun manageMockServer(arguments: com.fasterxml.jackson.databind.JsonNode): String {
            lastTool = "manage_mock_server"
            return "mock result"
        }

        override fun runBackwardCompatibilityCheck(arguments: com.fasterxml.jackson.databind.JsonNode): String {
            lastTool = "backward_compatibility_check"
            return "backward compatibility result"
        }

        override fun close() {
        }
    }
}
