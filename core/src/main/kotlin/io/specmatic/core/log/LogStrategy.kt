package io.specmatic.core.log

import io.specmatic.core.FailureReport
import io.specmatic.core.Result

interface LogStrategy : UsesIndentation, UsesBoundary {
    val printer: CompositePrinter
    var infoLoggingEnabled: Boolean

    fun keepReady(msg: LogMessage)
    fun exceptionString(e: Throwable, msg: String? = null): String
    fun ofTheException(e: Throwable, msg: String? = null): LogMessage
    fun log(e: Throwable, msg: String? = null)
    fun log(msg: String)
    fun log(msg: LogMessage)
    fun log(result: Result) {
        log(result.reportString())
    }
    fun log(report: FailureReport) {
        log(report.toText())
    }
    fun logError(e: Throwable)
    fun newLine()
    fun debug(msg: String): String
    fun debug(msg: LogMessage)
    fun debug(result: Result) {
        debug(StringLog(result.reportString()))
    }
    fun debug(e: Throwable, msg: String? = null)
    fun disableInfoLogging() {
        infoLoggingEnabled = false
    }

    fun enableInfoLogging() {
        infoLoggingEnabled = true
    }
}
