package application

import io.specmatic.core.loadSpecmaticConfigOrNull
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.UncaughtExceptionHandler
import io.specmatic.license.core.Executor
import io.specmatic.license.core.LicenseResolver
import io.specmatic.license.core.util.LicenseConfig
import io.specmatic.specmatic.executable.JULForwarder
import picocli.CommandLine

open class SpecmaticApplication {
    companion object {
        private const val SPECMATIC_DEBUG_MODE = "SPECMATIC_DEBUG_MODE"

        @JvmStatic
        fun main(args: Array<String>) {
            LicenseResolver.setCurrentExecutorIfNotSet(Executor.JAR)

            val specmaticConfig = loadSpecmaticConfigOrNull()
            specmaticConfig?.let {
                LicenseConfig.instance.utilization.shipDisabled = it.isTelemetryDisabled()
            }
            setupPicoCli()
            setupLogging()

            if (Flags.getBooleanValue(SPECMATIC_DEBUG_MODE)) {
                Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler())
            }

            val commandLine = CommandLine(SpecmaticCommand())
            SpecmaticCoreSubcommands.configure(commandLine)
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

        private fun setupPicoCli() {
            System.setProperty("picocli.usage.width", "auto")
        }

        private fun setupLogging() {
            JULForwarder.forward()
        }
    }
}
