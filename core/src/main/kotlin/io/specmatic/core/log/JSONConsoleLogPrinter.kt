package io.specmatic.core.log

object JSONConsoleLogPrinter: LogPrinter {
    override fun print(msg: LogMessage, indentation: String) {
        println(LogEnvelope.from(msg).toStringLiteral())
    }
}
