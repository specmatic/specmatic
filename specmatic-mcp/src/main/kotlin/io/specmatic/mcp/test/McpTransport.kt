package io.specmatic.mcp.test

import io.specmatic.mcp.constants.TRANSPORT_KIND
import io.specmatic.core.utilities.readEnvVarOrProperty

enum class McpTransport {
    SSE, STREAMABLE_HTTP;

    companion object {
        fun transportKindFromEnvVarOrProp(): McpTransport {
            val transportKind = readEnvVarOrProperty(TRANSPORT_KIND, TRANSPORT_KIND).orEmpty()
            return McpTransport.valueOf(transportKind)
        }
    }
}