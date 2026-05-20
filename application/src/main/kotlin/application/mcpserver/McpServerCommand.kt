package application.mcpserver

import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.log.configureLogging
import io.specmatic.license.core.cli.Category
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

@Command(
    name = "server",
    mixinStandardHelpOptions = true,
    description = ["Run Specmatic's MCP server over stdio"]
)
@Category("MCP capabilities")
class McpServerCommand : Callable<Int> {
    @Option(
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
            StdioMcpServer(SpecmaticMcpServer()).run()
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            1
        }
    }
}
