package application

import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.SystemExit
import io.specmatic.core.utilities.UncaughtExceptionHandler
import io.specmatic.specmatic.executable.JULForwarder

open class SpecmaticApplication {
    companion object {
        private const val SPECMATIC_DEBUG_MODE = "SPECMATIC_DEBUG_MODE"

        @JvmStatic
        fun main(args: Array<String>) {
            setupLogging()

            if (Flags.getBooleanValue(SPECMATIC_DEBUG_MODE)) {
                Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler())
            }

            val commandLine = SpecmaticCoreSubcommands.commandLine()

            when {
                args.isEmpty() -> commandLine.usage(System.out)
                else -> {
                    val exitCode = commandLine.execute(*args)
                    SystemExit.exitWith(exitCode)
                }
            }
        }

        private fun setupLogging() {
            JULForwarder.forward()
        }
    }
}
