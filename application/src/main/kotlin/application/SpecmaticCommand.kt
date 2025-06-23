package application

import application.backwardCompatibility.BackwardCompatibilityCheckCommandV2
import picocli.AutoComplete.GenerateCompletion
import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
        name = "specmatic",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider::class,
        scope = CommandLine.ScopeType.INHERIT,
        subcommands = [
            BackwardCompatibilityCheckCommandV2::class,
            CompareCommand::class,
            GenerateCompletion::class,
            ImportCommand::class,
            ProxyCommand::class,
            ExamplesCommand::class,
            StubCommand::class,
            TestCommand::class,
            CentralContractRepoReportCommand::class,
            ConfigCommand::class
        ]
)
class SpecmaticCommand : Callable<Int> {
    override fun call(): Int {
        return 0
    }
}
