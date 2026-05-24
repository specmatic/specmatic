package application.mcp

import application.SpecmaticApplication
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
        val originalOut = SpecmaticApplication.originalStdout
        System.setOut(System.err)

        configureLogging(
            io.specmatic.core.config.LoggingConfiguration.Companion.LoggingFromOpts(
                debug = verbose,
                textConsoleLog = false
            )
        )

        return try {
            runBlocking {
                SpecmaticMcpServer().use { it.run(outputStream = originalOut) }
            }
            0
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            1
        } finally {
            System.setOut(originalOut)
        }
    }
}
