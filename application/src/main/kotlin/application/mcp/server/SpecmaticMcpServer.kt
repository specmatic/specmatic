package application.mcp.server

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.specmatic.specmatic.executable.VersionInfo
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.ServiceLoader

class SpecmaticMcpServer(
    inputStream: InputStream = System.`in`,
    outputStream: OutputStream = System.out
) : AutoCloseable {
    private val enterpriseToolProviderIdentifier = "specmatic-enterprise-tools"
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
        val providers = ServiceLoader.load(McpToolProvider::class.java).toList()
        val hasEnterpriseProvider = providers.any { it.identifier == enterpriseToolProviderIdentifier }

        return providers.flatMap { provider ->
            val tools = provider.tools()
            if (hasEnterpriseProvider && provider is SpecmaticMcpToolProvider) {
                tools.filterNot { it.tool().name() == "run_contract_test" }
            } else {
                tools
            }
        }.also {
            System.err.println("Successfully registered ${it.size} tools.")
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
}
