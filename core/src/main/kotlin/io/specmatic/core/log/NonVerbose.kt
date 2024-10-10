package io.specmatic.core.log

import io.specmatic.core.utilities.exceptionCauseMessage

class NonVerbose(
    override val printer: CompositePrinter,
    override var infoLoggingEnabled: Boolean = true
) : LogStrategy {
    private val readyMessage = ReadyMessage()

    override fun keepReady(msg: LogMessage) {
        readyMessage.msg = msg
    }

    fun print(msg: LogMessage) {
        readyMessage.printLogString(printer)
        printer.print(msg)
    }

    override fun exceptionString(e: Throwable, msg: String?): String {
        return when(msg) {
            null -> exceptionCauseMessage(e)
            else -> "${msg}: ${exceptionCauseMessage(e)}"
        }
    }

    override fun ofTheException(e: Throwable, msg: String?): LogMessage {
        return NonVerboseExceptionLog(e, msg)
    }

    override fun log(e: Throwable, msg: String?) {
        print(NonVerboseExceptionLog(e, msg))
    }

    override fun log(msg: String) {
        if (infoLoggingEnabled) log(StringLog(msg))
    }

    override fun log(msg: LogMessage) {
        if (infoLoggingEnabled) print(msg)
    }

    override fun logError(e: Throwable) {
        log(e,"ERROR")
    }

    override fun newLine() {
        print(NewLineLogMessage)
    }

    override fun debug(msg: String): String { return msg }
    override fun debug(msg: LogMessage) {

    }

    override fun debug(e: Throwable, msg: String?) { }
}