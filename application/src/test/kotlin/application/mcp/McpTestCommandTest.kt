package application.mcp

import io.mockk.coEvery
import io.mockk.mockk
import io.specmatic.core.config.McpTransport
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.mcp.test.McpAutoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class McpTestCommandTest {
    @Test
    fun `cli arguments are passed correctly to McpAutoTest`(@TempDir tempDir: File) {
        val cmd = McpTestCommandMock().apply {
            baseUrl = "http://localhost:8080"
            transportKind = McpTransport.STREAMABLE_HTTP
            enableResiliencyTests = true
            dictionaryFile = tempDir.resolve("dict.json")
            bearerToken = "token123"
            filterTools = listOf("toolA", "toolB")
            skipTools = listOf("toolC")
        }

        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(cmd.capturedBaseUrl).isEqualTo("http://localhost:8080")
        assertThat(cmd.capturedTransport).isEqualTo(McpTransport.STREAMABLE_HTTP)
        assertThat(cmd.capturedEnableResiliency).isTrue()
        assertThat(cmd.capturedDictionaryFile!!.name).isEqualTo("dict.json")
        assertThat(cmd.capturedBearerToken).isEqualTo("token123")
        assertThat(cmd.capturedFilterTools).containsExactlyInAnyOrder("toolA", "toolB")
        assertThat(cmd.capturedSkipTools).containsExactly("toolC")
    }

    @Test
    fun `mcp config values are used when cli args are absent`(@TempDir tempDir: File) {
        val dictFile = tempDir.resolve("dict.json").apply { writeText("{}") }
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 2
            mcp:
              test:
                baseUrl: http://config-url:8080
                transportKind: STREAMABLE_HTTP
                enableResiliencyTests: true
                dictionaryFile: ${dictFile.canonicalPath}
                bearerToken: config-token
                filterTools:
                  - toolX
                  - toolY
                skipTools:
                  - toolZ
            """.trimIndent())
        }

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) { McpTestCommandMock() }
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(cmd.capturedBaseUrl).isEqualTo("http://config-url:8080")
        assertThat(cmd.capturedTransport).isEqualTo(McpTransport.STREAMABLE_HTTP)
        assertThat(cmd.capturedEnableResiliency).isTrue()
        assertThat(cmd.capturedDictionaryFile!!.canonicalPath).isEqualTo(dictFile.canonicalPath)
        assertThat(cmd.capturedBearerToken).isEqualTo("config-token")
        assertThat(cmd.capturedFilterTools).containsExactlyInAnyOrder("toolX", "toolY")
        assertThat(cmd.capturedSkipTools).containsExactly("toolZ")
    }

    @Test
    fun `cli overrides mcp config while config fills missing values`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: 2
            mcp:
              test:
                baseUrl: http://config-url:8080
                transportKind: STREAMABLE_HTTP
                enableResiliencyTests: false
                filterTools:
                  - from-config
            """.trimIndent())
        }

        val cmd = Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            McpTestCommandMock().apply {
                enableResiliencyTests = true
                filterTools = listOf("from-cli")
            }
        }

        cmd.call()
        assertThat(cmd.capturedBaseUrl).isEqualTo("http://config-url:8080")
        assertThat(cmd.capturedTransport).isEqualTo(McpTransport.STREAMABLE_HTTP)
        assertThat(cmd.capturedEnableResiliency).isTrue()
        assertThat(cmd.capturedFilterTools).containsExactlyInAnyOrder("from-config", "from-cli")
    }

    @Test
    fun `cli only works without mcp config`(@TempDir tempDir: File) {
        val cmd = McpTestCommandMock().apply {
            baseUrl = "http://cli-only"
            transportKind = McpTransport.STREAMABLE_HTTP
        }

        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(cmd.capturedBaseUrl).isEqualTo("http://cli-only")
        assertThat(cmd.capturedTransport).isEqualTo(McpTransport.STREAMABLE_HTTP)
        assertThat(cmd.capturedEnableResiliency).isFalse()
        assertThat(cmd.capturedFilterTools).isEmpty()
        assertThat(cmd.capturedSkipTools).isEmpty()
    }

    class McpTestCommandMock : McpTestCommand() {
        lateinit var capturedBaseUrl: String
        lateinit var capturedTransport: McpTransport
        var capturedEnableResiliency: Boolean? = null
        var capturedDictionaryFile: File? = null
        var capturedBearerToken: String? = null
        lateinit var capturedFilterTools: Set<String>
        lateinit var capturedSkipTools: Set<String>

        override fun createAutoTest(
            baseUrl: String,
            transport: McpTransport,
            enableResiliency: Boolean,
            dictionaryFile: File?,
            bearerToken: String?,
            filterTools: Set<String>,
            skipTools: Set<String>
        ): McpAutoTest {
            capturedBaseUrl = baseUrl
            capturedTransport = transport
            capturedEnableResiliency = enableResiliency
            capturedDictionaryFile = dictionaryFile
            capturedBearerToken = bearerToken
            capturedFilterTools = filterTools
            capturedSkipTools = skipTools
            return mockk<McpAutoTest> { coEvery { run() } returns 0 }
        }
    }
}