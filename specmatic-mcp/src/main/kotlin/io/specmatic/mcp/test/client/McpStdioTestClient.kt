package io.specmatic.mcp.test.client

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.specmatic.mcp.test.client.model.JsonRpcError
import io.specmatic.mcp.test.client.model.JsonRpcRequest
import io.specmatic.mcp.test.client.model.JsonRpcResponse
import io.specmatic.mcp.test.client.model.Tool
import io.specmatic.mcp.test.client.model.ToolsListResult
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class McpStdioConnectionException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class McpStdioTestClient(
    private val command: String,
    private val args: List<String> = emptyList(),
    private val environment: Map<String, String> = emptyMap(),
    private val workingDirectory: File? = null
) : McpTestClient {
    init {
        require(command.isNotBlank()) { "STDIO requires a server command" }
    }

    private val mapper = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "specmatic-mcp-stdio").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val shutdownHook = Thread({ close() }, "specmatic-mcp-stdio-shutdown")
    private lateinit var process: Process
    private var requestId = 0L

    override suspend fun connect(): McpStdioTestClient {
        process = try {
            ProcessBuilder(listOf(command) + args)
                .directory(workingDirectory)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .apply { environment().putAll(this@McpStdioTestClient.environment) }
                .start()
        } catch (e: IOException) {
            throw McpStdioConnectionException("Could not start STDIO MCP server '$command': ${e.message}", e)
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        initialize()
        sendInitializedNotification()
        return this
    }

    private val input by lazy { process.inputStream.bufferedReader(Charsets.UTF_8) }

    override suspend fun initialize(): JsonRpcResponse {
        val response = request(
            "initialize",
            mapOf(
                "protocolVersion" to "2025-11-25",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "Specmatic MCP Auto Test", "version" to "1.0.0")
            )
        )
        response.error?.let { throw McpStdioConnectionException("MCP initialization failed (${it.code}): ${it.message}") }
        val version = response.result?.path("protocolVersion")?.asText()
        if (version !in supportedVersions) {
            throw McpStdioConnectionException("STDIO MCP server selected an unsupported protocol version: $version")
        }
        return response
    }

    override suspend fun sendInitializedNotification() {
        exchange("notifications/initialized") {
            send(JsonRpcRequest(method = "notifications/initialized"))
        }
    }

    override suspend fun tools(): List<Tool> = buildList {
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val response = request("tools/list", cursor?.let { mapOf("cursor" to it) })
            response.error?.let { throw McpStdioConnectionException("MCP tool discovery failed (${it.code}): ${it.message}") }
            val result = response.result ?: throw McpStdioConnectionException("Missing MCP tools/list result")
            addAll(mapper.treeToValue(result, ToolsListResult::class.java).tools)
            cursor = result.get("nextCursor")?.takeUnless { it.isNull }?.asText()
            if (cursor != null && !seenCursors.add(cursor)) {
                throw McpStdioConnectionException("MCP tools/list returned a repeated pagination cursor")
            }
        } while (cursor != null)
    }

    override suspend fun toolCall(toolName: String, arguments: Map<String, Any?>): JsonRpcResponse =
        request("tools/call", mapOf("name" to toolName, "arguments" to arguments))

    private suspend fun request(method: String, params: Any?): JsonRpcResponse = exchange(method) {
        val id = ++requestId
        send(JsonRpcRequest(id = id, method = method, params = params))
        readResponse(id)
    }

    private suspend fun <T : Any> exchange(method: String, action: () -> T): T {
        ensureConnected()
        val result = try {
            withTimeoutOrNull(30_000) {
                CompletableFuture.supplyAsync(action, io).await()
            }
        } catch (e: IOException) {
            throw McpStdioConnectionException("STDIO MCP server failed during $method: ${e.message}", e)
        }
        return result ?: throw McpStdioConnectionException("Timed out waiting for STDIO MCP server during $method")
    }

    private fun send(message: Any) {
        val output = process.outputStream
        output.write(mapper.writeValueAsBytes(message))
        output.write('\n'.code)
        output.flush()
    }

    private fun readResponse(id: Long): JsonRpcResponse {
        while (true) {
            val line = input.readLine() ?: throw McpStdioConnectionException(exitMessage())
            val message = try {
                mapper.readTree(line)
            } catch (e: IOException) {
                throw McpStdioConnectionException("Expected a JSON-RPC message on stdout. Server logs belong on stderr.", e)
            }
            if (message == null || !message.isObject || message.path("jsonrpc").asText() != "2.0") {
                throw McpStdioConnectionException("Expected a JSON-RPC 2.0 message on stdout")
            }

            val messageId = message.get("id")?.takeUnless { it.isNull }
            val method = message.get("method")
            if (method != null) {
                if (messageId != null) {
                    val reply = if (method.asText() == "ping") {
                        JsonRpcResponse("2.0", messageId, result = mapper.createObjectNode())
                    } else {
                        JsonRpcResponse("2.0", messageId, error = JsonRpcError(-32601, "Method not found"))
                    }
                    send(reply)
                }
                continue
            }

            if (messageId?.isIntegralNumber != true || messageId.longValue() != id ||
                message.hasNonNull("result") == message.hasNonNull("error")
            ) {
                throw McpStdioConnectionException("Invalid JSON-RPC response for request $id")
            }
            return mapper.treeToValue(message, JsonRpcResponse::class.java)
        }
    }

    override fun ensureConnected() {
        if (!process.isAlive) throw McpStdioConnectionException(exitMessage())
    }

    private fun exitMessage(): String = if (process.isAlive) {
        "STDIO MCP server closed stdout"
    } else {
        "STDIO MCP server exited unexpectedly with status ${process.exitValue()}"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        io.shutdownNow()
        if (!::process.isInitialized) return

        val descendants = process.descendants().toList()
        try {
            // A blocked pipe write can hold the stream lock until the child exits.
            if (io.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                runCatching { process.outputStream.close() }
                if (process.waitFor(1, TimeUnit.SECONDS)) return
            }
            descendants.forEach { it.destroy() }
            // Signal the process without closing pipes that the I/O worker may still hold.
            process.toHandle().destroy()
            process.waitFor(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            if (process.isAlive) process.toHandle().destroyForcibly()
            if (Thread.currentThread() != shutdownHook) {
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        }
    }

    private companion object {
        val supportedVersions = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
    }
}
