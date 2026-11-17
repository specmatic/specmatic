package io.specmatic.core.config

import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class McpConfigurationTest {
    @Test
    fun `rejects each HTTP field when HTTP options must be absent`() {
        for (config in listOf(
            McpTestConfiguration(baseUrl = "http://localhost"),
            McpTestConfiguration(bearerToken = "token")
        )) {
            assertThatThrownBy { config.ensureHttpOptionsAbsent() }
                .isInstanceOf(ContractException::class.java)
                .hasMessage("mcp.test.baseUrl and mcp.test.bearerToken are only supported with the STREAMABLE_HTTP transport")
        }
    }

    @Test
    fun `rejects each stdio field when stdio options must be absent`() {
        for (config in listOf(
            McpTestConfiguration(command = "python"),
            McpTestConfiguration(args = listOf("server.py")),
            McpTestConfiguration(env = mapOf("MODE" to "test")),
            McpTestConfiguration(workingDirectory = "server files")
        )) {
            assertThatThrownBy { config.ensureStdioOptionsAbsent() }
                .isInstanceOf(ContractException::class.java)
                .hasMessage("mcp.test.command, mcp.test.args, mcp.test.workingDirectory and mcp.test.env require the STDIO transport")
        }
    }

    @Test
    fun `shared fields do not count as transport options`() {
        val config = McpTestConfiguration(
            dictionaryFile = "dictionary.json", enableResiliencyTests = true,
            filterTools = listOf("echo"), skipTools = listOf("calc")
        )
        config.ensureHttpOptionsAbsent()
        config.ensureStdioOptionsAbsent()
    }
}
