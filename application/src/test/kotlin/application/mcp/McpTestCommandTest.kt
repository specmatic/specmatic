package application.mcp

import io.mockk.coEvery
import io.mockk.mockk
import io.specmatic.core.config.McpTransport
import io.specmatic.core.examples.module.FAILURE_EXIT_CODE
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.mcp.test.McpAutoTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import picocli.CommandLine

class McpTestCommandTest {
    @ParameterizedTest
    @ValueSource(strings = ["--command=python", "--arg=server", "--working-directory=server", "--env=MODE=test"])
    fun `http transport rejects stdio options`(option: String) {
        val cmd = McpTestCommandMock()
        CommandLine(cmd).parseArgs("--transport-kind", "STREAMABLE_HTTP", "--url", "http://localhost", option)

        assertThat(cmd.call()).isEqualTo(FAILURE_EXIT_CODE)
        assertThat(cmd.autoTestWasCreated).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["--url=http://localhost", "--bearer-token=token"])
    fun `stdio transport rejects http options`(option: String) {
        val cmd = McpTestCommand()
        CommandLine(cmd).parseArgs("--transport-kind", "STDIO", "--command", "unused", option)

        assertThat(cmd.call()).isEqualTo(FAILURE_EXIT_CODE)
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3])
    fun `http transport rejects stdio fields in http config`(version: Int, @TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
                version: $version
                mcp:
                  test:
                    baseUrl: http://localhost
                    command: python
            """.trimIndent())
        }
        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = McpTestCommandMock()
            assertThat(cmd.call()).isEqualTo(FAILURE_EXIT_CODE)
            assertThat(cmd.autoTestWasCreated).isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3])
    fun `http CLI override does not validate unused stdio config`(version: Int, @TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
                version: $version
                mcp:
                  test:
                    transportKind: STDIO
                    command: python
            """.trimIndent())
        }
        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = McpTestCommandMock()
            CommandLine(cmd).parseArgs("--transport-kind", "STREAMABLE_HTTP", "--url", "http://cli")
            assertThat(cmd.call()).isZero()
            assertThat(cmd.capturedBaseUrl).isEqualTo("http://cli")
        }
    }

    @Test
    fun `stdio options parse together including repeated arguments and environment overrides`() {
        val cmd = McpTestCommand()
        CommandLine(cmd).parseArgs(
            "--transport-kind", "STDIO", "--command", "python",
            "--arg=-m", "--arg=server", "--working-directory", "server files",
            "--env", "MODE=test", "--env", "DEBUG=true"
        )

        assertThat(cmd.stdio.serverCommand).isEqualTo("python")
        assertThat(cmd.stdio.serverArgs).containsExactly("-m", "server")
        assertThat(cmd.stdio.workingDirectory).isEqualTo(File("server files"))
        assertThat(cmd.stdio.serverEnvironment).containsEntry("MODE", "test").containsEntry("DEBUG", "true")
    }

    @Test
    fun `http options parse together`() {
        val cmd = McpTestCommand()
        CommandLine(cmd).parseArgs("--url", "http://localhost:8080", "--bearer-token", "token")

        assertThat(cmd.streamableHttp.baseUrl).isEqualTo("http://localhost:8080")
        assertThat(cmd.streamableHttp.bearerToken).isEqualTo("token")
    }

    @Test
    fun `partial http group allows config to supply url`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("version: 2\nmcp:\n  test:\n    baseUrl: http://config-url:8080\n")
        }
        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = McpTestCommandMock()
            CommandLine(cmd).parseArgs("--bearer-token", "cli-token")

            assertThat(cmd.call()).isZero()
            assertThat(cmd.capturedBaseUrl).isEqualTo("http://config-url:8080")
            assertThat(cmd.capturedBearerToken).isEqualTo("cli-token")
        }
    }

    @Test
    fun `cli arguments are passed correctly to McpAutoTest`(@TempDir tempDir: File) {
        val cmd = McpTestCommandMock().apply {
            streamableHttp.baseUrl = "http://localhost:8080"
            transportKind = McpTransport.STREAMABLE_HTTP
            enableResiliencyTests = true
            dictionaryFile = tempDir.resolve("dict.json")
            streamableHttp.bearerToken = "token123"
            filterTools = listOf("toolA", "toolB")
            skipTools = listOf("toolC")
        }

        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(cmd.capturedBaseUrl).isEqualTo("http://localhost:8080")
        assertThat(cmd.capturedEnableResiliency).isTrue()
        assertThat(cmd.capturedDictionaryFile!!.name).isEqualTo("dict.json")
        assertThat(cmd.capturedBearerToken).isEqualTo("token123")
        assertThat(cmd.capturedFilterTools).containsExactlyInAnyOrder("toolA", "toolB")
        assertThat(cmd.capturedSkipTools).containsExactly("toolC")
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3])
    fun `mcp config values are used and an omitted transport defaults to Streamable HTTP`(
        version: Int, @TempDir tempDir: File
    ) {
        val dictFile = tempDir.resolve("dict.json").apply { writeText("{}") }
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText("""
            version: $version
            mcp:
              test:
                baseUrl: http://config-url:8080
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

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = McpTestCommandMock()
            val exitCode = cmd.call()
            assertThat(exitCode).isEqualTo(0)
            assertThat(cmd.capturedBaseUrl).isEqualTo("http://config-url:8080")
            assertThat(cmd.capturedEnableResiliency).isTrue()
            assertThat(cmd.capturedDictionaryFile!!.canonicalPath).isEqualTo(dictFile.canonicalPath)
            assertThat(cmd.capturedBearerToken).isEqualTo("config-token")
            assertThat(cmd.capturedFilterTools).containsExactlyInAnyOrder("toolX", "toolY")
            assertThat(cmd.capturedSkipTools).containsExactly("toolZ")
        }
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

        Flags.using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            val cmd = McpTestCommandMock().apply {
                enableResiliencyTests = true
                filterTools = listOf("from-cli")
            }

            cmd.call()
            assertThat(cmd.capturedBaseUrl).isEqualTo("http://config-url:8080")
            assertThat(cmd.capturedEnableResiliency).isTrue()
            assertThat(cmd.capturedFilterTools).containsExactlyInAnyOrder("from-config", "from-cli")
        }
    }

    @Test
    fun `cli only works without mcp config`(@TempDir tempDir: File) {
        val cmd = McpTestCommandMock().apply {
            streamableHttp.baseUrl = "http://cli-only"
            transportKind = McpTransport.STREAMABLE_HTTP
        }

        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(cmd.capturedBaseUrl).isEqualTo("http://cli-only")
        assertThat(cmd.capturedEnableResiliency).isFalse()
        assertThat(cmd.capturedFilterTools).isEmpty()
        assertThat(cmd.capturedSkipTools).isEmpty()
    }

    @Test
    fun `call fails when base url is absent from cli and config`() {
        val cmd = McpTestCommandMock().apply {
            transportKind = McpTransport.STREAMABLE_HTTP
        }

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(FAILURE_EXIT_CODE)
        assertThat(cmd.autoTestWasCreated).isFalse()
    }

    @Test
    fun `call fails when transport kind is absent from cli and config`() {
        val cmd = McpTestCommandMock().apply {
            streamableHttp.baseUrl = "http://localhost:8080"
        }

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(FAILURE_EXIT_CODE)
        assertThat(cmd.autoTestWasCreated).isFalse()
    }

    class McpTestCommandMock : McpTestCommand() {
        var autoTestWasCreated: Boolean = false
        lateinit var capturedBaseUrl: String
        var capturedEnableResiliency: Boolean? = null
        var capturedDictionaryFile: File? = null
        var capturedBearerToken: String? = null
        lateinit var capturedFilterTools: Set<String>
        lateinit var capturedSkipTools: Set<String>

        override fun createStreamableHttpAutoTest(
            baseUrl: String,
            enableResiliency: Boolean,
            dictionaryFile: File?,
            bearerToken: String?,
            filterTools: Set<String>,
            skipTools: Set<String>
        ): McpAutoTest {
            autoTestWasCreated = true
            capturedBaseUrl = baseUrl
            capturedEnableResiliency = enableResiliency
            capturedDictionaryFile = dictionaryFile
            capturedBearerToken = bearerToken
            capturedFilterTools = filterTools
            capturedSkipTools = skipTools
            return mockk<McpAutoTest> { coEvery { run() } returns 0 }
        }
    }
}
