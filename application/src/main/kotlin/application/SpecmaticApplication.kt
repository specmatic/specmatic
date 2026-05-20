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
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val configClass = Class.forName("io.github.oshai.kotlinlogging.KotlinLoggingConfiguration")
                val configInstance = configClass.getField("INSTANCE").get(null)
                configClass.methods.firstOrNull { it.name == "setLogStartupMessage" }?.invoke(configInstance, false)
                Class.forName("io.github.oshai.kotlinlogging.KotlinLogging")
            } catch (_: Throwable) {
            }

            LicenseResolver.setCurrentExecutorIfNotSet(Executor.JAR)

            val specmaticConfig = loadSpecmaticConfigOrNull()
            specmaticConfig?.let {
                LicenseConfig.instance.utilization.shipDisabled = it.isTelemetryDisabled()
            }
            setupPicoCli()
            setupLogging()

            Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler())

            val commandLine = CommandLine(SpecmaticCommand())
            SpecmaticCoreSubcommands.configure(commandLine)
            if (shouldPrintVersionBanner(args)) {
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

        internal fun shouldPrintVersionBanner(args: Array<String>): Boolean {
            if (args.any { it == "-V" || it == "--version" || it == "generate-completion" }) {
                return false
            }

            return !(args.size >= 2 && args[0] == "mcp" && args[1] == "server")
        }

        private fun setupPicoCli() {
            System.setProperty("picocli.usage.width", "auto")
        }

        private fun setupLogging() {
            JULForwarder.forward()
        }
    }
}
