package application.mcp.server.spi

import io.modelcontextprotocol.server.McpServerFeatures

interface McpToolProvider {
    fun tools(): List<McpServerFeatures.SyncToolSpecification>
}