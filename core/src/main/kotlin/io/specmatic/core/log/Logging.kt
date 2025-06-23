package io.specmatic.core.log

var logger: LogStrategy = newLogger()

fun newLogger(): LogStrategy = newLogger(listOf(ConsolePrinter))

fun newLogger(printers: List<LogPrinter>): LogStrategy = ThreadSafeLog(NonVerbose(CompositePrinter(printers)))

fun resetLogger() {
    logger = NonVerbose(CompositePrinter())
}

@Suppress("unused")
val DebugLogger = ThreadSafeLog(Verbose(CompositePrinter()))

@Suppress("unused")
val InfoLogger = ThreadSafeLog(NonVerbose(CompositePrinter()))

@Suppress("unused")
fun <T> withLogger(
    logStrategy: LogStrategy,
    fn: () -> T,
): T {
    val oldLogger = logger
    logger = logStrategy
    try {
        return fn()
    } finally {
        logger = oldLogger
    }
}

fun logException(fn: () -> Unit): Int =
    try {
        fn()
        0
    } catch (e: Throwable) {
        logger.log(e)
        1
    }

fun consoleLog(event: String) {
    consoleLog(StringLog(event))
}

fun consoleLog(event: LogMessage) {
    LogTail.append(event)
    logger.log(event)
}

fun consoleLog(e: Throwable) {
    LogTail.append(logger.ofTheException(e))
    logger.log(e)
}

fun consoleLog(
    e: Throwable,
    msg: String,
) {
    LogTail.append(logger.ofTheException(e, msg))
    logger.log(e, msg)
}

fun consoleDebug(event: String) {
    consoleDebug(StringLog(event))
}

fun consoleDebug(event: LogMessage) {
    LogTail.append(event)
    logger.debug(event)
}

fun consoleDebug(e: Throwable) {
    LogTail.append(logger.ofTheException(e))
    logger.debug(e)
}

fun consoleDebug(
    e: Throwable,
    msg: String,
) {
    LogTail.append(logger.ofTheException(e, msg))
    logger.debug(e, msg)
}

val dontPrintToConsole = { event: LogMessage ->
    LogTail.append(event)
}

val ignoreLog = { _: LogMessage -> }
