package application.mcp.server

import io.modelcontextprotocol.server.McpServerFeatures

interface McpToolProvider {
    val identifier: String
    fun tools(): List<McpServerFeatures.SyncToolSpecification>
}
