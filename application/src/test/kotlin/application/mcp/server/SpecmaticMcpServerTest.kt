package application.mcp.server

import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.specmatic.core.utilities.SystemExitException
import org.junit.jupiter.api.AfterEach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SpecmaticMcpServerTest {

    private val server = SpecmaticMcpServer(
        inputStream = ByteArrayInputStream(ByteArray(0)),
        outputStream = ByteArrayOutputStream()
    )

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

        assertThat(tools.getValue("run_contract_test").inputSchema().required())
            .containsExactly("openApiSpec", "apiBaseUrl")
        assertThat(tools.getValue("run_resiliency_test").inputSchema().required())
            .containsExactly("openApiSpec", "apiBaseUrl")
        assertThat(tools.getValue("manage_mock_server").inputSchema().required())
            .containsExactly("command")
        assertThat(tools.getValue("backward_compatibility_check").inputSchema().required())
            .isEmpty()
        assertThat(tools.getValue("manage_mock_server").inputSchema().properties().getValue("port"))
            .isEqualTo(mapOf("type" to "integer", "description" to "Port number for the mock server"))
    }

    @Test
    fun `run should keep server process alive while stdio input is open`() {
        val inputStream = BlockingInputStream()
        val localServer = SpecmaticMcpServer(inputStream = inputStream, outputStream = ByteArrayOutputStream())
        val runReturned = AtomicBoolean(false)
        val runThread = Thread {
            localServer.use {
                it.run()
                runReturned.set(true)
            }
        }

        runThread.start()

        try {
            assertThat(inputStream.readStarted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(runReturned.get()).isFalse()
            assertThat(runThread.isAlive).isTrue()
        } finally {
            inputStream.close()
            runThread.join(5_000)
        }
    }

    @Test
    fun `safeToolCall should return success when block returns a string`() {
        val result = invokeSafeToolCall("Hello World")
        
        assertThat(result.isError).isFalse()
        assertThat(result.content()).hasSize(1)
        assertThat((result.content()[0] as McpSchema.TextContent).text()).isEqualTo("Hello World")
    }

    @Test
    fun `safeToolCall should catch SystemExitException and return error report`() {
        val result = invokeSafeToolCall {
            throw SystemExitException(1, "Exit called")
        }
        
        assertThat(result.isError).isTrue()
        assertThat((result.content()[0] as McpSchema.TextContent).text()).contains("## Specmatic MCP Tool Error")
        assertThat((result.content()[0] as McpSchema.TextContent).text()).contains("> **Error:** Exit called")
    }

    @Test
    fun `safeToolCall should catch general exception and return error report`() {
        val result = invokeSafeToolCall {
            throw RuntimeException("Something went wrong")
        }
        
        assertThat(result.isError).isTrue()
        assertThat((result.content()[0] as McpSchema.TextContent).text()).contains("## Specmatic MCP Tool Error")
        assertThat((result.content()[0] as McpSchema.TextContent).text()).contains("> **Error:** Something went wrong")
    }

    @Test
    fun `safeToolCall should handle exceptions with null message`() {
        val result = invokeSafeToolCall {
            throw NullPointerException()
        }
        
        assertThat(result.isError).isTrue()
        assertThat((result.content()[0] as McpSchema.TextContent).text()).contains("> **Error:** NullPointerException")
    }

    private fun invokeSafeToolCall(returnValue: String): McpSchema.CallToolResult {
        return invokeSafeToolCall { returnValue }
    }

    private fun invokeSafeToolCall(block: () -> String): McpSchema.CallToolResult {
        val method = SpecmaticMcpServer::class.java.getDeclaredMethod("safeToolCall", Function0::class.java)
        method.isAccessible = true
        return method.invoke(server, block) as McpSchema.CallToolResult
    }

    private fun registeredTools() = underlyingServer().listTools().associateBy { it.name() }

    private fun underlyingServer(): McpSyncServer {
        val field = SpecmaticMcpServer::class.java.getDeclaredField("server")
        field.isAccessible = true
        return field.get(server) as McpSyncServer
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val closeLatch = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            closeLatch.await()
            return -1
        }

        override fun close() {
            closeLatch.countDown()
        }
    }
}
