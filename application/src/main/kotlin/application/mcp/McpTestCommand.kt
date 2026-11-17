package application.mcp

import io.specmatic.core.config.McpTestConfiguration
import io.specmatic.core.config.McpTransport
import io.specmatic.core.examples.module.FAILURE_EXIT_CODE
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.pattern.ContractException
import io.specmatic.license.core.cli.Category
import io.specmatic.mcp.test.McpAutoTest
import io.specmatic.mcp.test.client.McpStdioTestClient
import io.specmatic.mcp.test.client.McpStreamableHttpTestClient
import java.io.File
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(
    name = "test",
    mixinStandardHelpOptions = true,
    description = ["Runs auto tests against a mcp server"]
)
@Category("AI Assisted Contract Programming")
open class McpTestCommand : Callable<Int> {
    @field:ArgGroup(exclusive = false, heading = "%nSTDIO transport options:%n")
    var stdio = StdioOptions()

    @field:ArgGroup(exclusive = false, heading = "%nStreamable HTTP transport options:%n")
    var streamableHttp = StreamableHttpOptions()

    @Option(
        names = ["--transport-kind"],
        description = ["Kind of transport mechanism being used by the mcp server. Valid values: \${COMPLETION-CANDIDATES}"],
        required = false
    )
    var transportKind: McpTransport? = null

    @Option(
        names = ["--enable-resiliency-tests"],
        description = ["Run resiliency tests"],
        required = false
    )
    var enableResiliencyTests: Boolean? = null

    @Option(
        names = ["--dictionary-file"],
        description = ["Dictionary file path"],
    )
    var dictionaryFile: File? = null

    @Option(
        names = ["--filter-tools"],
        description = ["Run the auto test for specified tools; provide a comma-separated list or repeat the flag"],
        split = ","
    )
    var filterTools: List<String> = emptyList()

    @Option(
        names = ["--skip-tools"],
        description = ["Skip the auto test for the specified tools; provide a comma-separated list or repeat the flag"],
        split = ","
    )
    var skipTools: List<String> = emptyList()

    @Option(
        names = ["--verbose", "-v"],
        description = ["Enable verbose logging"],
    )
    var verbose: Boolean? = null

    private val specmaticConfig by lazy { loadSpecmaticConfigIfAvailableElseDefault() }
    private val mcpConfig by lazy { specmaticConfig.getMcpConfiguration() }

    override fun call(): Int {
        McpBaseCommand.configureLogger(verbose)
        try {
            val autoTest = when (effectiveTransport()) {
                McpTransport.STDIO -> createStdioAutoTest()
                McpTransport.STREAMABLE_HTTP -> createStreamableHttpAutoTest()
            }
            return runBlocking { autoTest.run() }
        } catch (e: Throwable) {
            e.printStackTrace()
            return FAILURE_EXIT_CODE
        }
    }

    private fun createStreamableHttpAutoTest(): McpAutoTest {
        stdio.ensureAbsent()
        mcpConfig?.test?.takeIf { it.transportKind == McpTransport.STREAMABLE_HTTP }?.ensureStdioOptionsAbsent()
        return createStreamableHttpAutoTest(
            baseUrl = streamableHttp.effectiveBaseUrl(mcpConfig?.test),
            enableResiliency = effectiveEnableResiliency(),
            dictionaryFile = effectiveDictionaryFile(),
            bearerToken = effectiveBearerToken(),
            filterTools = effectiveFilterTools(),
            skipTools = effectiveSkipTools()
        )
    }

    private fun createStdioAutoTest(): McpAutoTest {
        val config = mcpConfig?.test?.takeIf { it.transportKind == McpTransport.STDIO }
        config?.ensureHttpOptionsAbsent()
        streamableHttp.ensureAbsent()
        val command = stdio.effectiveCommand(config)
        return McpAutoTest(
            client = McpStdioTestClient(
                command = command,
                args = stdio.serverArgs ?: config?.args.orEmpty(),
                environment = config?.env.orEmpty() + stdio.serverEnvironment,
                workingDirectory = stdio.workingDirectory ?: config?.workingDirectory?.let(::File)
            ),
            enableResiliency = effectiveEnableResiliency(),
            dictionaryFile = effectiveDictionaryFile(),
            filterTools = effectiveFilterTools(),
            skipTools = effectiveSkipTools()
        )
    }

    protected open fun createStreamableHttpAutoTest(
        baseUrl: String,
        enableResiliency: Boolean,
        dictionaryFile: File?,
        bearerToken: String?,
        filterTools: Set<String>,
        skipTools: Set<String>
    ): McpAutoTest = McpAutoTest(
        client = McpStreamableHttpTestClient(baseUrl, bearerToken),
        enableResiliency = enableResiliency,
        dictionaryFile = dictionaryFile,
        filterTools = filterTools,
        skipTools = skipTools
    )

    private fun effectiveTransport(): McpTransport =
        transportKind ?: mcpConfig?.test?.transportKind
        ?: throw ContractException("Please provide transportKind through CLI arguments or Specmatic Config")

    private fun effectiveFilterTools(): Set<String> = buildSet {
        mcpConfig?.test?.filterTools?.let { addAll(it) }
        if (filterTools.isNotEmpty()) addAll(filterTools)
    }

    private fun effectiveSkipTools(): Set<String> = buildSet {
        mcpConfig?.test?.skipTools?.let { addAll(it) }
        if (skipTools.isNotEmpty()) addAll(skipTools)
    }

    private fun effectiveEnableResiliency(): Boolean = enableResiliencyTests ?: mcpConfig?.test?.enableResiliencyTests ?: false
    private fun effectiveDictionaryFile(): File? = dictionaryFile ?: mcpConfig?.test?.getDictionaryIfExists()
    private fun effectiveBearerToken(): String? = streamableHttp.bearerToken ?: mcpConfig?.test?.bearerToken

    class StdioOptions {
        @Option(names = ["--command"], description = ["Executable that starts the stdio MCP server"])
        var serverCommand: String? = null

        @Option(names = ["--arg"], description = ["Argument to the stdio server executable. Repeat for each argument."])
        var serverArgs: List<String>? = null

        @Option(names = ["--working-directory"], description = ["Working directory of the stdio MCP server"])
        var workingDirectory: File? = null

        @Option(names = ["--env"], description = ["Environment override for the stdio server as NAME=VALUE. Repeat for each override."])
        var serverEnvironment: Map<String, String> = emptyMap()

        fun ensureAbsent() {
            if (serverCommand != null || serverArgs != null || workingDirectory != null || serverEnvironment.isNotEmpty()) {
                throw ContractException("--command, --arg, --working-directory and --env require the STDIO transport")
            }
        }

        fun effectiveCommand(config: McpTestConfiguration?): String =
            (serverCommand ?: config?.command)?.takeIf { it.isNotBlank() }
                ?: throw ContractException("Please provide the STDIO server executable through --command or mcp.test.command")
    }

    class StreamableHttpOptions {
        @Option(names = ["--url"], description = ["URL of the mcp server"], required = false)
        var baseUrl: String? = null

        @Option(
            names = ["--bearer-token"],
            description = ["Bearer Access Token"],
        )
        var bearerToken: String? = null

        fun ensureAbsent() {
            if (baseUrl != null || bearerToken != null) {
                throw ContractException("--url and --bearer-token are only supported with the STREAMABLE_HTTP transport")
            }
        }

        fun effectiveBaseUrl(config: McpTestConfiguration?): String =
            baseUrl ?: config?.baseUrl
                ?: throw ContractException("Please provide baseUrl through CLI arguments or Specmatic Config")
    }
}
