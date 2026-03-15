package io.specmatic.core.log

import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class LogRunContext(
    val runId: String,
    val command: String,
    val component: String,
)

object LogContext {
    @Volatile
    private var context: LogRunContext = defaultContext()

    private fun defaultContext(): LogRunContext {
        return LogRunContext(
            runId = UUID.randomUUID().toString(),
            command = "",
            component = "specmatic",
        )
    }

    fun startRun(
        command: String? = null,
        component: String? = null,
        runId: String = UUID.randomUUID().toString(),
    ) {
        context = LogRunContext(
            runId = runId,
            command = command ?: "",
            component = component ?: "specmatic",
        )
    }

    fun current(): LogRunContext = context
}

object LogEnvelope {
    fun from(message: LogMessage): JSONObjectValue {
        val context = LogContext.current()
        val envelope =
            mutableMapOf<String, Value>(
                "timestamp" to StringValue(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()),
                "level" to StringValue(message.level()),
                "eventType" to StringValue(message.eventType()),
                "runId" to StringValue(context.runId),
                "command" to StringValue(context.command),
                "component" to StringValue(context.component),
                "thread" to StringValue(Thread.currentThread().name),
                "payload" to message.toJSONObject(),
            )

        message.correlationId()?.let { envelope["correlationId"] = StringValue(it) }
        return JSONObjectValue(envelope)
    }
}
