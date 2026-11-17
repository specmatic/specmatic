package io.specmatic.mcp.test.client

import io.specmatic.mcp.test.client.model.JsonRpcResponse
import io.specmatic.mcp.test.client.model.Tool
import io.specmatic.mcp.test.logWithTag

interface McpTestClient {
    suspend fun initialize(): JsonRpcResponse
    suspend fun sendInitializedNotification()
    suspend fun connect(): McpTestClient
    suspend fun tools(): List<Tool>
    suspend fun toolCall(toolName: String, arguments: Map<String, Any?>): JsonRpcResponse
    fun ensureConnected() {}
    fun close()
}

suspend fun <T : Any> McpTestClient.use(block: suspend (McpTestClient) -> T): T {
    try {
        logWithTag("Sending initialization request to the MCP server...")
        this.connect()
        logWithTag("Initialization has completed.")
        return block(this)
    } finally {
        this.close()
    }
}
