package io.specmatic.core.log

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.rendering.TextColors.brightBlue
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold

object ConsolePrinter: LogPrinter {
    private val terminal = Terminal()

    override fun print(msg: LogMessage, indentation: String) {
        val message = msg.toLogString().prependIndent(indentation)
        // TODO - figure out better way
//        val command = LogContext.current().command.trim()
//        val contextualPrefix = if (command.isBlank()) "" else "[$command] "
        val contextualPrefix = ""
        terminal.println(contextualPrefix + formatForConsole(msg.level(), message))
    }

    private fun formatForConsole(level: String, message: String): String {
        val normalized = level.uppercase()
        return when (normalized) {
            "ERROR", "FATAL" -> (brightRed + bold)("[x] $message")
            "WARN", "WARNING" -> (yellow + bold)("[!] $message")
            "DEBUG" -> brightCyan("[.] $message")
            "TRACE" -> brightBlue("[.] $message")
            else -> message
        }
    }
}
