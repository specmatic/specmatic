package io.specmatic.core.log

import io.specmatic.core.config.ConfigLoggingLevel
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault

var logger: LogStrategy = ThreadSafeLog(NonVerbose(CompositePrinter()))

fun logStrategyFromConfig(): LogStrategy {
    val specmaticConfig = loadSpecmaticConfigIfAvailableElseDefault()
    return logStrategyFromConfig(specmaticConfig.getLogConfigurationOrDefault())
}

fun logStrategyFromConfig(logConfig: LoggingConfiguration): LogStrategy {
    return newLogger(printers = textPrinters(logConfig) + jsonPrinters(logConfig), logConfig)
}

private fun textPrinters(config: LoggingConfiguration): List<LogPrinter> {
    if (!config.hasTextConfiguration()) return listOf(ConsolePrinter)
    val textConfig = config.textConfigurationOrDefault()
    val prefix = textConfig.getLogFilePrefixOrDefault()
    val directory = textConfig.getLogDirectory()?.canonicalPath
    return buildList {
        if (directory != null) {
            add(TextFilePrinter(LogDirectory(directory, prefix, ".log")))
        }

        if (textConfig.isConsoleLoggingEnabled(default = true)) {
            add(ConsolePrinter)
        }
    }
}

private fun jsonPrinters(config: LoggingConfiguration): List<LogPrinter> {
    if (!config.hasJsonConfiguration()) return emptyList()
    val jsonConfig = config.jsonConfigurationOrDefault()
    val prefix = jsonConfig.getLogFilePrefixOrDefault()
    val directory = jsonConfig.getLogDirectory()?.canonicalPath
    return buildList {
        if (directory != null) {
            add(JSONFilePrinter(LogDirectory(directory, prefix, "-json.log")))
        }

        if (jsonConfig.isConsoleLoggingEnabled(default = false)) {
            add(JSONConsoleLogPrinter)
        }
    }
}

fun newLogger(printers: List<LogPrinter>, config: LoggingConfiguration): LogStrategy {
    val base = CompositePrinter(printers)
    val verbosity = when (config.levelOrDefault()) {
        ConfigLoggingLevel.DEBUG -> Verbose(base)
        ConfigLoggingLevel.INFO -> NonVerbose(base)
    }

    return ThreadSafeLog(verbosity)
}

@Suppress("unused") // Being used in other modules
fun resetLogger() {
    val base = logStrategyFromConfig()
    logger = if (logger is AgentConsoleLogger) AgentConsoleLogger(base) else base
}

fun setLoggerUsing(logConfig: LoggingConfiguration) {
    val base = logStrategyFromConfig(logConfig)
    logger = if (logger is AgentConsoleLogger) AgentConsoleLogger(base) else base
}

/**
 * Installs [AgentConsoleLogger] for the duration of [fn], then restores the previous logger.
 * Idempotent if an agent logger is already installed.
 */
fun <T> withAgentConsoleLogger(fn: () -> T): T {
    if (logger is AgentConsoleLogger) return fn()
    val previous = logger
    logger = AgentConsoleLogger(previous)
    return try {
        fn()
    } finally {
        logger = previous
    }
}

fun shouldEmitToConsole(kind: ConsoleLogKind): Boolean {
    val current = logger
    return if (current is AgentConsoleLogger) current.shouldPrint(kind) else true
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

fun consoleLog(event: String, kind: ConsoleLogKind = ConsoleLogKind.Default) {
    consoleLog(StringLog(event), kind)
}

fun consoleLog(event: LogMessage, kind: ConsoleLogKind = ConsoleLogKind.Default) {
    LogTail.append(event)
    if (!shouldEmitToConsole(kind)) return
    logger.log(event)
}

fun consoleLog(e: Throwable, kind: ConsoleLogKind = ConsoleLogKind.Default) {
    LogTail.append(logger.ofTheException(e))
    if (!shouldEmitToConsole(kind)) return
    logger.log(e)
}

fun consoleLog(
    e: Throwable,
    msg: String,
    kind: ConsoleLogKind = ConsoleLogKind.Default,
) {
    LogTail.append(logger.ofTheException(e, msg))
    if (!shouldEmitToConsole(kind)) return
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

/** HttpClient / stub request dumps — quiet only when [AgentConsoleLogger] is installed. */
val httpDumpLog: (LogMessage) -> Unit = { event ->
    consoleLog(event, ConsoleLogKind.HttpDump)
}

val ignoreLog = { _: LogMessage -> }
