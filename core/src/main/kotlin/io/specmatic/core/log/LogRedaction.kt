package io.specmatic.core.log

import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value

data class LogRedactionSettings(
    val enabled: Boolean,
    val headersToRedact: Set<String>,
    val jsonKeysToRedact: Set<String>,
    val mask: String,
)

object LogRedactionContext {
    @Volatile
    private var settings: LogRedactionSettings = fromConfig(LoggingConfiguration.default())

    fun start(config: LoggingConfiguration) {
        settings = fromConfig(config)
    }

    fun current(): LogRedactionSettings = settings

    private fun fromConfig(config: LoggingConfiguration): LogRedactionSettings {
        val redaction = config.redactionConfigurationOrDefault()

        return LogRedactionSettings(
            enabled = redaction.isEnabledOrDefault(),
            headersToRedact = redaction.headersOrDefault().map { it.lowercase() }.toSet(),
            jsonKeysToRedact = redaction.jsonKeysOrDefault().map { it.lowercase() }.toSet(),
            mask = redaction.maskOrDefault(),
        )
    }
}

object LogRedactor {
    fun headers(headers: Map<String, String>, additionalHeaderNames: Set<String> = emptySet()): Map<String, String> {
        val settings = LogRedactionContext.current()
        if (!settings.enabled || headers.isEmpty()) return headers

        val configuredHeaderNames = settings.headersToRedact + additionalHeaderNames.map { it.lowercase() }
        return redactMapValues(headers, configuredHeaderNames, settings.mask)
    }

    fun flatMapByKey(values: Map<String, String>): Map<String, String> {
        val settings = LogRedactionContext.current()
        if (!settings.enabled || values.isEmpty()) return values

        return redactMapValues(values, settings.jsonKeysToRedact, settings.mask)
    }

    fun value(value: Value): Value {
        val settings = LogRedactionContext.current()
        if (!settings.enabled) return value

        return redactValue(value, settings)
    }

    private fun redactValue(value: Value, settings: LogRedactionSettings): Value {
        return when (value) {
            is JSONObjectValue -> {
                val redactedMap =
                    value.jsonObject.mapValues { (key, nestedValue) ->
                        if (key.lowercase() in settings.jsonKeysToRedact) {
                            StringValue(settings.mask)
                        } else {
                            redactValue(nestedValue, settings)
                        }
                    }
                JSONObjectValue(redactedMap)
            }

            is JSONArrayValue -> JSONArrayValue(value.list.map { redactValue(it, settings) })
            else -> value
        }
    }

    private fun redactMapValues(values: Map<String, String>, keysToRedact: Set<String>, mask: String): Map<String, String> {
        return values.mapValues { (key, value) ->
            if (key.lowercase() in keysToRedact) mask else value
        }
    }
}
