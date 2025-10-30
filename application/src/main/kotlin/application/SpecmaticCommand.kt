package application

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import application.mcp.McpBaseCommand
import io.specmatic.license.core.cli.CliConfigurer
import io.specmatic.reporter.commands.ReporterSubcommands
import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "specmatic",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
    scope = CommandLine.ScopeType.INHERIT,
    subcommands = [
    ]
)
class SpecmaticCommand : Callable<Int> {
    override fun call(): Int {
        return 0
    }
}


object SpecmaticCoreSubcommands : CliConfigurer {
    override fun subcommands() = arrayOf(
        BackwardCompatibilityCheckCommandV2(),
        CompareCommand(),
        ImportCommand(),
        ProxyCommand(),
        ExamplesCommand(),
        StubCommand(),
        TestCommand(),
        CentralContractRepoReportCommand(),
        ConfigCommand(),
        McpBaseCommand(),
        *ReporterSubcommands.subcommands(),
    )
}
