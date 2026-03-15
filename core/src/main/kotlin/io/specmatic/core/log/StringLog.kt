package io.specmatic.core.log

import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue

class StringLog(private val msg: String): LogMessage {
    override fun level(): String {
        val normalized = msg.trimStart().uppercase()
        return when {
            normalized.startsWith("ERROR") -> "ERROR"
            normalized.startsWith("WARN") -> "WARNING"
            normalized.startsWith("DEBUG") -> "DEBUG"
            normalized.startsWith("TRACE") -> "TRACE"
            else -> "INFO"
        }
    }

    override fun eventType(): String {
        return "text"
    }

    override fun toJSONObject(): JSONObjectValue {
        return JSONObjectValue(mapOf("message" to StringValue(msg)))
    }

    override fun toLogString(): String {
        return msg
    }
}
