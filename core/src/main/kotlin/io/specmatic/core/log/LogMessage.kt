package io.specmatic.core.log

import io.specmatic.core.value.JSONObjectValue

interface LogMessage {
    fun toJSONObject(): JSONObjectValue
    fun toLogString(): String
    fun level(): String {
        return "INFO"
    }

    fun eventType(): String {
        return this::class.simpleName ?: "LogMessage"
    }

    fun correlationId(): String? {
        return LogCorrelationContext.current()
    }

    fun toOneLineString(): String {
        return toLogString().lines().joinToString(" ") { it.trim() }
    }
}
