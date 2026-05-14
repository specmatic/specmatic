package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import io.specmatic.core.config.v3.TemplateOrValue
import io.specmatic.core.config.v3.TemplateOrValue.Companion.resolve
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

private fun <T : Any> wrapTemplateValue(value: T): TemplateOrValue<T> = TemplateOrValue.Value(value)

data class LogOutputConfig(
    val directory: TemplateOrValue<String>? = null,
    val console: TemplateOrValue<Boolean>? = null,
    @field:JsonAlias("logPrefix") val logFilePrefix: TemplateOrValue<String>? = null
) {
    @JsonIgnore
    fun getLogDirectory(): File? = directory?.resolve()?.let(::File)

    @JsonIgnore
    fun getLogFilePrefixOrDefault(): String = logFilePrefix?.resolve() ?: "specmatic"

    @JsonIgnore
    fun isConsoleLoggingEnabled(default: Boolean): Boolean = console?.resolve() ?: default

    fun overrideMergeWith(other: LogOutputConfig?): LogOutputConfig {
        if (other == null) return this
        return from(
            directory = other.directory?.resolve() ?: this.directory?.resolve(),
            console = other.console?.resolve() ?: this.console?.resolve(),
            logFilePrefix = other.logFilePrefix?.resolve() ?: this.logFilePrefix?.resolve()
        )
    }

    companion object {
        fun default(): LogOutputConfig {
            return from(directory = null, console = true)
        }

        fun from(
            directory: String? = null,
            console: Boolean? = null,
            logFilePrefix: String? = null
        ) = LogOutputConfig(
            directory = directory?.let(::wrapTemplateValue),
            console = console?.let(::wrapTemplateValue),
            logFilePrefix = logFilePrefix?.let(::wrapTemplateValue)
        )
    }
}

data class LoggingConfiguration(
    val level: TemplateOrValue<ConfigLoggingLevel>? = null,
    val json: TemplateOrValue<LogOutputConfig>? = null,
    val text: TemplateOrValue<LogOutputConfig>? = null
) {
    fun levelOrDefault(): ConfigLoggingLevel = level?.resolve() ?: ConfigLoggingLevel.INFO

    fun hasJsonConfiguration(): Boolean = json?.resolve() != null
    fun jsonConfigurationOrDefault(): LogOutputConfig = json?.resolve() ?: LogOutputConfig.default()

    fun hasTextConfiguration(): Boolean = text?.resolve() != null
    fun textConfigurationOrDefault(): LogOutputConfig = text?.resolve() ?: LogOutputConfig.default()

    fun overrideMergeWith(other: LoggingConfiguration?): LoggingConfiguration {
        if (other == null) return this
        return from(
            level = other.level?.resolve() ?: this.level?.resolve(),
            json = json?.resolve().nonNullElse(other.json?.resolve(), LogOutputConfig::overrideMergeWith),
            text = text?.resolve().nonNullElse(other.text?.resolve(), LogOutputConfig::overrideMergeWith)
        )
    }

    companion object {
        fun default(): LoggingConfiguration {
            return from(level = ConfigLoggingLevel.INFO)
        }

        fun from(data: LoggingFromOpts): LoggingConfiguration {
            return from(
                level = if (data.debug == true) ConfigLoggingLevel.DEBUG else null,
                text = if (data.textConsoleLog != null && data.textLogDirectory != null) {
                    LogOutputConfig.from(directory = data.textLogDirectory.path, console = data.textConsoleLog, logFilePrefix = data.logPrefix)
                } else {
                    null
                },
                json = if (data.jsonConsoleLog != null && data.jsonLogDirectory != null) {
                    LogOutputConfig.from(directory = data.jsonLogDirectory.path, console = data.jsonConsoleLog, logFilePrefix = data.logPrefix)
                } else {
                    null
                },
            )
        }

        fun from(
            level: ConfigLoggingLevel? = null,
            json: LogOutputConfig? = null,
            text: LogOutputConfig? = null
        ) = LoggingConfiguration(
            level = level?.let(::wrapTemplateValue),
            json = json?.let(::wrapTemplateValue),
            text = text?.let(::wrapTemplateValue)
        )

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
