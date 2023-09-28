package application

import `in`.specmatic.core.APPLICATION_NAME_LOWER_CASE
import `in`.specmatic.core.Configuration.Companion.globalConfigFileName
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.utilities.exitWithMessage
import `in`.specmatic.core.utilities.loadSources
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(name = "install", description = ["Clone the git repositories declared in the manifest"], mixinStandardHelpOptions = true)
class InstallCommand: Callable<Unit> {

    @CommandLine.Option(names = ["--targetDirectory"], description = ["Directory in which the git repository will be cloned"])
    var targetDirectory: String = System.getProperty("user.home")
    override fun call() {
        val userHome = File(targetDirectory)
        val workingDirectory = userHome.resolve(".$APPLICATION_NAME_LOWER_CASE/repos")

        val sources = try { loadSources(globalConfigFileName) } catch(e: ContractException) { exitWithMessage(e.failure().toReport().toText()) }

        for(source in sources) {
            println("Installing $source")
            source.install(workingDirectory)
        }
    }
}
