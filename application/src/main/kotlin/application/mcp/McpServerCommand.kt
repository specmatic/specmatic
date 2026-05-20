package application.mcp

import application.mcp.server.SpecmaticMcpServer
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.log.configureLogging
import io.specmatic.license.core.cli.Category
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "server",
    mixinStandardHelpOptions = true,
    description = ["Run Specmatic's MCP server over stdio"]
)
@Category("MCP capabilities")
class McpServerCommand : Callable<Int> {
    @CommandLine.Option(
        names = ["--verbose", "-v"],
        description = ["Enable verbose logging on stderr"]
    )
    var verbose: Boolean? = null

    override fun call(): Int {
        configureLogging(
            LoggingConfiguration.Companion.LoggingFromOpts(
                debug = verbose,
                textConsoleLog = false
            )
        )

        return try {
            runBlocking {
                SpecmaticMcpServer().use { it.run() }
            }
            0
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            1
        }
    }
}
