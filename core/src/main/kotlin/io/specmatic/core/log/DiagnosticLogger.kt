package io.specmatic.core.log

interface DiagnosticLogger {
    fun emit(event: DiagnosticEvent)
}

fun isDiagnosticLoggingActive(): Boolean {
    return LoggingScope.diagnosticLogger() !== NoOpDiagnosticLogger
}

inline fun emitThrowableFailureIfNotLogged(
    throwable: Throwable,
    emitEvent: () -> Unit,
) {
    if (LoggedFailureScope.wasLogged(throwable)) return

    emitEvent()
    LoggedFailureScope.markLogged(throwable)
}

object NoOpDiagnosticLogger : DiagnosticLogger {
    override fun emit(event: DiagnosticEvent) {
    }
}

internal fun setDefaultDiagnosticLogger(logger: DiagnosticLogger) {
    LoggingScope.setDefaultDiagnosticLogger(logger)
}
