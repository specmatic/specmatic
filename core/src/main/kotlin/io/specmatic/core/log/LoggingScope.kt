package io.specmatic.core.log

import java.util.concurrent.atomic.AtomicReference

object LoggingScope {
    private val defaultDiagnosticLogger = AtomicReference<DiagnosticLogger>(NoOpDiagnosticLogger)
    private val diagnosticLoggerOverride = object : InheritableThreadLocal<DiagnosticLogger?>() {}
    private val executionContextOverride = object : InheritableThreadLocal<ExecutionContext?>() {}
    private val loggingEnabledOverride = object : InheritableThreadLocal<Boolean?>() {}

    fun diagnosticLogger(): DiagnosticLogger {
        if (!isLoggingEnabled()) {
            return NoOpDiagnosticLogger
        }

        return diagnosticLoggerOverride.get() ?: defaultDiagnosticLogger.get()
    }

    fun executionContext(): ExecutionContext = executionContextOverride.get() ?: ExecutionContext.Unknown

    fun isLoggingEnabled(): Boolean = loggingEnabledOverride.get() ?: true

    fun setDefaultDiagnosticLogger(logger: DiagnosticLogger) {
        defaultDiagnosticLogger.set(logger)
    }

    fun <T> withLogger(logger: DiagnosticLogger, fn: () -> T): T {
        val old = diagnosticLoggerOverride.get()
        diagnosticLoggerOverride.set(logger)
        return try {
            fn()
        } finally {
            if (old == null) diagnosticLoggerOverride.remove() else diagnosticLoggerOverride.set(old)
        }
    }

    fun <T> withExecutionContext(context: ExecutionContext, fn: () -> T): T {
        val old = executionContextOverride.get()
        executionContextOverride.set(context)
        return try {
            LoggedFailureScope.withFreshScope(fn)
        } finally {
            if (old == null) executionContextOverride.remove() else executionContextOverride.set(old)
        }
    }

    fun <T> withLoggingEnabled(enabled: Boolean, fn: () -> T): T {
        val old = loggingEnabledOverride.get()
        loggingEnabledOverride.set(enabled)
        return try {
            fn()
        } finally {
            if (old == null) loggingEnabledOverride.remove() else loggingEnabledOverride.set(old)
        }
    }
}
