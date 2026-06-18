package application.mcp.server

import io.modelcontextprotocol.server.McpServerFeatures

interface McpToolProvider {
    fun tools(): List<McpServerFeatures.SyncToolSpecification>
}
