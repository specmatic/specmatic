package io.specmatic.core.log

import io.specmatic.core.config.ConfigLoggingLevel
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.readEnvVarOrProperty
import io.specmatic.core.loadSpecmaticConfigIfAvailableElseDefault

private const val NEW_LOGGER_SWITCH = "SPECMATIC_NEW_LOGGER"

var logger: LogStrategy = createDefaultLogStrategy()

private fun createDefaultLogStrategy(): LogStrategy {
    val defaultConfig = LoggingConfiguration.default()
    return if (newLoggingEnabled()) {
        setDefaultDiagnosticLogger(diagnosticLoggerFromConfig(defaultConfig))
        NoOpLogStrategy
    } else {
        setDefaultDiagnosticLogger(NoOpDiagnosticLogger)
        ThreadSafeLog(NonVerbose(CompositePrinter()))
    }
}

fun logStrategyFromConfig(): LogStrategy {
    val specmaticConfig = loadSpecmaticConfigIfAvailableElseDefault()
    return logStrategyFromConfig(specmaticConfig.getLogConfigurationOrDefault())
}

fun logStrategyFromConfig(logConfig: LoggingConfiguration): LogStrategy {
    if (newLoggingEnabled()) {
        setDefaultDiagnosticLogger(diagnosticLoggerFromConfig(logConfig))
        return NoOpLogStrategy
    }
    setDefaultDiagnosticLogger(NoOpDiagnosticLogger)
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
    logger = logStrategyFromConfig()
}

fun setLoggerUsing(logConfig: LoggingConfiguration) {
    logger = logStrategyFromConfig(logConfig)
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

fun <T> withLogger(
    logger: DiagnosticLogger,
    fn: () -> T,
): T {
    return LoggingScope.withLogger(logger, fn)
}

fun <T> withoutLogging(fn: () -> T): T {
    return LoggingScope.withLoggingEnabled(false) {
        if (newLoggingEnabled()) {
            fn()
        } else {
            withLogger(NoOpLogStrategy, fn)
        }
    }
}

fun <T> withExecutionContext(
    context: ExecutionContext,
    fn: () -> T,
): T {
    return LoggingScope.withExecutionContext(context, fn)
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

internal fun diagnosticLoggerFromConfig(config: LoggingConfiguration): DiagnosticLogger {
    val consoleEnabled = when {
        config.hasTextConfiguration() -> config.textConfigurationOrDefault().isConsoleLoggingEnabled(default = true)
        else -> true
    }

    return MordantDiagnosticLogger(consoleEnabled = consoleEnabled)
}

internal fun newLoggingEnabled(): Boolean {
    return parseBooleanSwitch(readEnvVarOrProperty(NEW_LOGGER_SWITCH, NEW_LOGGER_SWITCH)) ?: false
}

private fun parseBooleanSwitch(value: String?): Boolean? {
    val normalized = value?.trim()?.lowercase() ?: return null
    return when (normalized) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
