package application.mcpserver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StdioMcpServerTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `stdio server responds to initialize request`() {
        val requestJson = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}
        """.trimIndent()
        val requestBody = requestJson.toByteArray(Charsets.UTF_8)
        val requestFrame = "Content-Length: ${requestBody.size}\r\n\r\n".toByteArray(Charsets.UTF_8) + requestBody

        val output = ByteArrayOutputStream()
        val server = StdioMcpServer(
            server = SpecmaticMcpServer(toolExecutor = FakeToolExecutor()),
            input = ByteArrayInputStream(requestFrame),
            output = output
        )

        val exitCode = server.run()

        val responseBytes = output.toByteArray()
        val responseText = responseBytes.toString(Charsets.UTF_8)

        assertThat(exitCode).isEqualTo(0)
        assertThat(responseText).contains("Content-Length:")
        assertThat(responseText).contains("\"protocolVersion\":\"2024-11-05\"")
        assertThat(responseText).contains("\"serverInfo\"")
    }

    private class FakeToolExecutor : SpecmaticMcpToolExecutor {
        override fun runContractTest(arguments: com.fasterxml.jackson.databind.JsonNode, resiliency: Boolean): String = "ok"
        override fun manageMockServer(arguments: com.fasterxml.jackson.databind.JsonNode): String = "ok"
        override fun runBackwardCompatibilityCheck(arguments: com.fasterxml.jackson.databind.JsonNode): String = "ok"
        override fun close() {
        }
    }
}
