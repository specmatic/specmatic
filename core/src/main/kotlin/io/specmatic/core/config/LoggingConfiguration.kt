package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import java.io.File

enum class ConfigLoggingLevel(private val level: String) {
    INFO("INFO"),
    DEBUG("DEBUG");

    @JsonValue
    fun value(): String = level

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String?): ConfigLoggingLevel = entries.firstOrNull { it.level.equals(value, ignoreCase = true) } ?: INFO
    }
}

data class LogOutputConfig(val directory: String?, val console: Boolean?, val logFilePrefix: String? = null) {
    @JsonIgnore
    fun getLogDirectory(): File? = directory?.let(::File)

    @JsonIgnore
    fun getLogFilePrefixOrDefault(): String = logFilePrefix ?: "specmatic"

    @JsonIgnore
    fun isConsoleLoggingEnabled(default: Boolean): Boolean = console ?: default

    fun overrideMergeWith(other: LogOutputConfig?): LogOutputConfig {
        if (other == null) return this
        return LogOutputConfig(
            directory = other.directory ?: this.directory,
            console = other.console ?: this.console,
            logFilePrefix = other.logFilePrefix ?: this.logFilePrefix
        )
    }

    companion object {
        fun default(): LogOutputConfig {
            return LogOutputConfig(directory = null, console = true)
        }
    }
}

data class LoggingConfiguration(
    val level: ConfigLoggingLevel? = null,
    val json: LogOutputConfig? = null,
    val text: LogOutputConfig? = null
) {
    fun levelOrDefault(): ConfigLoggingLevel = level ?: ConfigLoggingLevel.INFO

    fun hasJsonConfiguration(): Boolean = json != null
    fun jsonConfigurationOrDefault(): LogOutputConfig = json ?: LogOutputConfig.default()

    fun hasTextConfiguration(): Boolean = text != null
    fun textConfigurationOrDefault(): LogOutputConfig = text ?: LogOutputConfig.default()

    fun overrideMergeWith(other: LoggingConfiguration?): LoggingConfiguration {
        if (other == null) return this
        return LoggingConfiguration(
            level = other.level ?: this.level,
            json = json.nonNullElse(other.json, LogOutputConfig::overrideMergeWith),
            text = text.nonNullElse(other.text, LogOutputConfig::overrideMergeWith)
        )
    }

    companion object {
        fun default(): LoggingConfiguration {
            return LoggingConfiguration(level = ConfigLoggingLevel.INFO)
        }

        fun from(data: LoggingFromOpts): LoggingConfiguration {
            return LoggingConfiguration(
                level = if (data.debug == true) ConfigLoggingLevel.DEBUG else null,
                text = if (data.textConsoleLog != null && data.textLogDirectory != null) {
                    LogOutputConfig(directory = data.textLogDirectory.path, console = data.textConsoleLog, logFilePrefix = data.logPrefix)
                } else {
                    null
                },
                json = if (data.jsonConsoleLog != null && data.jsonLogDirectory != null) {
                    LogOutputConfig(directory = data.jsonLogDirectory.path, console = data.jsonConsoleLog, logFilePrefix = data.logPrefix)
                } else {
                    null
                },
            )
        }

        data class LoggingFromOpts(
            val debug: Boolean? = null,
            val textLogDirectory: File? = null,
            val textConsoleLog: Boolean? = null,
            val jsonLogDirectory: File? = null,
            val jsonConsoleLog: Boolean? = null,
            val logPrefix: String? = null
        )
    }
}

fun <T> T?.nonNullElse(other: T?, orElse: (T, T) -> T): T? = when {
    this == null -> other
    other == null -> this
    else -> orElse(this, other)
}
