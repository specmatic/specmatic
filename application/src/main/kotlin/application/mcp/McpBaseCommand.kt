package application.mcp

import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.log.configureLogging
import io.specmatic.license.core.cli.Category
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "mcp",
    mixinStandardHelpOptions = true,
    scope = CommandLine.ScopeType.INHERIT,
    subcommands = [McpTestCommand::class],
    description = ["Execute Specmatic MCP capabilities"]
)
@Category("MCP capabilities")
class McpBaseCommand : Callable<Int> {
    override fun call(): Int = 0

    companion object {
        fun configureLogger(verbose: Boolean?) {
            configureLogging(LoggingConfiguration.Companion.LoggingFromOpts(debug = verbose))
        }
    }
}
