package application.mcp

import io.specmatic.core.config.McpTransport
import io.specmatic.core.examples.module.FAILURE_EXIT_CODE
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault
import io.specmatic.core.pattern.ContractException
import io.specmatic.license.core.cli.Category
import io.specmatic.mcp.test.McpAutoTest
import java.io.File
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(
    name = "test",
    mixinStandardHelpOptions = true,
    description = ["Runs auto tests against a mcp server"]
)
@Category("AI Assisted Contract Programming")
open class McpTestCommand : Callable<Int> {
    @Option(names = ["--url"], description = ["URL of the mcp server"], required = false)
    var baseUrl: String? = null

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
        names = ["--bearer-token"],
        description = ["Bearer Access Token"],
    )
    var bearerToken: String? = null

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

    private val specmaticConfig = loadSpecmaticConfigIfAvailableElseDefault()
    private val mcpConfig = specmaticConfig.getMcpConfiguration()

    override fun call(): Int {
        McpBaseCommand.configureLogger(verbose)
        try {
            val exitCode: Int = runBlocking {
                createAutoTest(
                    baseUrl = effectiveBaseUrl(),
                    transport = effectiveTransport(),
                    enableResiliency = effectiveEnableResiliency(),
                    dictionaryFile = effectiveDictionaryFile(),
                    bearerToken = effectiveBearerToken(),
                    filterTools = effectiveFilterTools(),
                    skipTools = effectiveSkipTools()
                ).run()
            }
            return exitCode
        } catch (e: Throwable) {
            e.printStackTrace()
            return FAILURE_EXIT_CODE
        }
    }

    protected open fun createAutoTest(
        baseUrl: String,
        transport: McpTransport,
        enableResiliency: Boolean,
        dictionaryFile: File?,
        bearerToken: String?,
        filterTools: Set<String>,
        skipTools: Set<String>
    ): McpAutoTest = McpAutoTest(baseUrl, transport, enableResiliency, dictionaryFile, bearerToken, filterTools, skipTools)

    private fun effectiveTransport(): McpTransport {
        if (transportKind != null) return transportKind as McpTransport
        if (mcpConfig?.test?.transportKind != null) return mcpConfig.test.transportKind as McpTransport
        throw ContractException("Please provide transportKind through CLI arguments or Specmatic Config")
    }

    private fun effectiveBaseUrl(): String {
        if (baseUrl != null) return baseUrl as String
        if (mcpConfig?.test?.baseUrl != null) return mcpConfig.test.baseUrl
        throw ContractException("Please provide baseUrl through CLI arguments or Specmatic Config")
    }

    private fun effectiveFilterTools(): Set<String> = buildSet {
        mcpConfig?.test?.filterTools?.let { addAll(it) }
        if (filterTools.isNotEmpty()) addAll(filterTools)
    }

    private fun effectiveSkipTools(): Set<String> = buildSet {
        mcpConfig?.test?.skipTools?.let { addAll(it) }
        if (skipTools.isNotEmpty()) addAll(skipTools)
    }

    private fun effectiveEnableResiliency(): Boolean = enableResiliencyTests ?: mcpConfig?.test?.enableResiliencyTests ?: false
    private fun effectiveDictionaryFile(): File? = dictionaryFile ?: mcpConfig?.test?.dictionaryFile
    private fun effectiveBearerToken(): String? = bearerToken ?: mcpConfig?.test?.bearerToken
}
