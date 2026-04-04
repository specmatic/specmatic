package io.specmatic.core.log

import io.specmatic.core.utilities.exceptionCauseMessage

object NoOpLogStrategy : LogStrategy, UsesIndentationWithHelpers by UsesIndentationImpl(), UsesBoundaryWithHelpers by UsesBoundaryImpl() {
    override val printer: CompositePrinter = CompositePrinter(emptyList())
    override var infoLoggingEnabled: Boolean = true

    override fun keepReady(msg: LogMessage) {
    }

    override fun exceptionString(e: Throwable, msg: String?): String {
        return msg ?: exceptionCauseMessage(e)
    }

    override fun ofTheException(e: Throwable, msg: String?): LogMessage {
        return NonVerboseExceptionLog(e, msg)
    }

    override fun log(e: Throwable, msg: String?) {
    }

    override fun log(msg: String) {
    }

    override fun log(msg: LogMessage) {
    }

    override fun logError(e: Throwable) {
    }

    override fun newLine() {
    }

    override fun debug(msg: String): String = msg

    override fun debug(msg: LogMessage) {
    }

    override fun debug(e: Throwable, msg: String?) {
    }
}
