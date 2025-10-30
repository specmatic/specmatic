package application.mcp

import io.specmatic.core.log.CompositePrinter
import io.specmatic.core.log.ConsolePrinter
import io.specmatic.core.log.NonVerbose
import io.specmatic.core.log.Verbose
import io.specmatic.core.log.logger
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
        fun configureLogger(verbose: Boolean) {
            logger =
                if (verbose) {
                    Verbose(CompositePrinter(listOf(ConsolePrinter)))
                } else {
                    NonVerbose(CompositePrinter(listOf(ConsolePrinter)))
                }
        }
    }
}
