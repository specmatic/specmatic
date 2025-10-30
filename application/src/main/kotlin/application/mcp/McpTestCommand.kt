package application.mcp

import io.specmatic.core.examples.module.FAILURE_EXIT_CODE
import io.specmatic.license.core.cli.Category
import io.specmatic.mcp.constants.BASE_URL
import io.specmatic.mcp.constants.TRANSPORT_KIND
import io.specmatic.mcp.test.McpAutoTest
import io.specmatic.mcp.test.McpTransport
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
class McpTestCommand : Callable<Int> {
    @Option(
        names = ["--url"],
        description = ["URL of the mcp server"],
        required = true,
    )
    lateinit var baseUrl: String

    @Option(
        names = ["--transport-kind"],
        description = ["Kind of transport mechanism being used by the mcp server. Valid values: \${COMPLETION-CANDIDATES}"],
        required = false
    )
    var transportKind: McpTransport = McpTransport.STREAMABLE_HTTP

    @Option(
        names = ["--enable-resiliency-tests"],
        description = ["Run resiliency tests"],
        required = false,
        defaultValue = "false"
    )
    var enableResiliencyTests: Boolean? = false

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
        defaultValue = "false"
    )
    var verbose: Boolean = false

    override fun call(): Int {
        McpBaseCommand.configureLogger(verbose)
        try {
            System.setProperty(BASE_URL, baseUrl)
            System.setProperty(TRANSPORT_KIND, transportKind.name)
            var exitCode = 0

            runBlocking {
                exitCode = McpAutoTest(
                    baseUrl = baseUrl,
                    transport = transportKind,
                    enableResiliency = enableResiliencyTests == true,
                    dictionaryFile = dictionaryFile,
                    bearerToken = bearerToken,
                    filterTools = filterTools.toSet(),
                    skipTools = skipTools.toSet()
                ).run()
            }
            return exitCode
        } catch (e: Throwable) {
            e.printStackTrace()
            return FAILURE_EXIT_CODE
        }
    }
}
