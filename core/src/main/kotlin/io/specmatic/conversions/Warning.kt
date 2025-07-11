package io.specmatic.conversions

import io.specmatic.core.log.LogMessage
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue

class Warning(
    private val problem: String,
    private val implications: String,
    private val resolution: String,
) : LogMessage {
    override fun toJSONObject(): JSONObjectValue {
        return JSONObjectValue(
            mapOf(
                "problem" to StringValue(problem),
                "reason" to StringValue(implications),
                "resolution" to StringValue(resolution),
            ),
        )
    }

    override fun toLogString(): String {
        return "WARNING: $problem $implications $resolution"
    }
}