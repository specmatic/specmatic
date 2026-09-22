package application

import io.specmatic.core.lifecycle.SpecmaticLifecycle
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.UncaughtExceptionHandler
import io.specmatic.license.core.Executor
import io.specmatic.specmatic.executable.JULForwarder
import picocli.CommandLine

open class SpecmaticApplication {
    companion object {
        @JvmStatic
        val originalStdout: java.io.PrintStream = System.out

        @JvmStatic
        fun main(args: Array<String>) {
            SpecmaticLifecycle.initialize(Executor.JAR, args.toList())
            val commandLine = createCommandLine()
            redirectStdoutToStderrIfMcpServer(args)
            setupPicoCli()
            setupLogging()

            Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler())

            if (args.none { it == "-V" || it == "--version" || it == "generate-completion" }) {
                commandLine.printVersionHelp(System.out)
                println()
            }

            when {
                args.isEmpty() -> commandLine.usage(System.out)
                else -> {
                    val exitCode = commandLine.execute(*args)
                    SystemExit.exitWith(exitCode)
                }
            }
        }

        private fun redirectStdoutToStderrIfMcpServer(args: Array<String>) {
            val commandLine = createCommandLine().apply {
                isUnmatchedArgumentsAllowed = true
            }

            val parseResult = try {
                commandLine.parseArgs(*args)
            } catch (_: CommandLine.ParameterException) {
                null
            }

            val subcommandPath = parseResult
                ?.asCommandLineList()
                ?.drop(1)
                ?.map { it.commandName }

            if (subcommandPath == listOf("mcp", "server")) {
                System.setOut(System.err)
            }
        }

        private fun createCommandLine(): CommandLine =
            CommandLine(SpecmaticCommand()).also(SpecmaticCoreSubcommands::configure)

        private fun setupPicoCli() {
            System.setProperty("picocli.usage.width", "auto")
        }

        private fun setupLogging() {
            JULForwarder.forward()
        }
    }
}
