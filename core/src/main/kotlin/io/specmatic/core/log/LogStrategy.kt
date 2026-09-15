package io.specmatic.core.log

interface LogStrategy : UsesIndentation, UsesBoundary {
    val printer: CompositePrinter
    var infoLoggingEnabled: Boolean

    fun keepReady(msg: LogMessage)
    fun exceptionString(e: Throwable, msg: String? = null): String
    fun ofTheException(e: Throwable, msg: String? = null): LogMessage
    fun log(e: Throwable, msg: String? = null)
    fun log(msg: String)
    fun log(msg: LogMessage)
    fun logError(e: Throwable)
    fun newLine()
    fun debug(msg: String): String
    fun debug(msg: LogMessage)
    fun debug(e: Throwable, msg: String? = null)
    fun disableInfoLogging() {
        infoLoggingEnabled = false
    }

    fun enableInfoLogging() {
        infoLoggingEnabled = true
    }

    fun shouldPrintToConsole(kind: ConsoleLogEmission): Boolean = true
}