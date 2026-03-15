package io.specmatic.core.config

import com.fasterxml.jackson.annotation.JsonAlias
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

data class LogOutputConfig(val directory: String?, val console: Boolean?, @field:JsonAlias("logPrefix") val logFilePrefix: String? = null) {
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

data class LogRedactionConfig(
    val enabled: Boolean? = null,
    val headers: Set<String>? = null,
    val jsonKeys: Set<String>? = null,
    val mask: String? = null,
) {
    @JsonIgnore
    fun isEnabledOrDefault(): Boolean = enabled ?: true

    @JsonIgnore
    fun headersOrDefault(): Set<String> = DEFAULT_HEADERS + headers.orEmpty()

    @JsonIgnore
    fun jsonKeysOrDefault(): Set<String> = DEFAULT_JSON_KEYS + jsonKeys.orEmpty()

    @JsonIgnore
    fun maskOrDefault(): String = mask ?: DEFAULT_MASK

    fun overrideMergeWith(other: LogRedactionConfig?): LogRedactionConfig {
        if (other == null) return this

        return LogRedactionConfig(
            enabled = other.enabled ?: this.enabled,
            headers = this.headers.orEmpty() + other.headers.orEmpty(),
            jsonKeys = this.jsonKeys.orEmpty() + other.jsonKeys.orEmpty(),
            mask = other.mask ?: this.mask,
        )
    }

    companion object {
        private const val DEFAULT_MASK = "***REDACTED***"
        private val DEFAULT_HEADERS =
            setOf(
                "Authorization",
                "Proxy-Authorization",
                "Cookie",
                "Set-Cookie",
                "X-Api-Key",
            )
        private val DEFAULT_JSON_KEYS =
            setOf(
                "password",
                "passwd",
                "secret",
                "apiKey",
                "api_key",
                "token",
                "access_token",
                "refresh_token",
                "id_token",
                "client_secret",
            )

        fun default(): LogRedactionConfig = LogRedactionConfig(enabled = true)
    }
}

data class LoggingConfiguration(
    val level: ConfigLoggingLevel? = null,
    val json: LogOutputConfig? = null,
    val text: LogOutputConfig? = null,
    val redaction: LogRedactionConfig? = null,
) {
    fun levelOrDefault(): ConfigLoggingLevel = level ?: ConfigLoggingLevel.INFO

    fun hasJsonConfiguration(): Boolean = json != null
    fun jsonConfigurationOrDefault(): LogOutputConfig = json ?: LogOutputConfig.default()

    fun hasTextConfiguration(): Boolean = text != null
    fun textConfigurationOrDefault(): LogOutputConfig = text ?: LogOutputConfig.default()
    fun redactionConfigurationOrDefault(): LogRedactionConfig = redaction ?: LogRedactionConfig.default()

    fun overrideMergeWith(other: LoggingConfiguration?): LoggingConfiguration {
        if (other == null) return this
        return LoggingConfiguration(
            level = other.level ?: this.level,
            json = json.nonNullElse(other.json, LogOutputConfig::overrideMergeWith),
            text = text.nonNullElse(other.text, LogOutputConfig::overrideMergeWith),
            redaction = redaction.nonNullElse(other.redaction, LogRedactionConfig::overrideMergeWith),
        )
    }

    companion object {
        fun default(): LoggingConfiguration {
            return LoggingConfiguration(level = ConfigLoggingLevel.INFO)
        }

        fun from(data: LoggingFromOpts): LoggingConfiguration {
            return LoggingConfiguration(
                level = if (data.debug == true) ConfigLoggingLevel.DEBUG else null,
                text = logOutputConfig(data.textLogDirectory, data.textConsoleLog, data.logPrefix),
                json = logOutputConfig(data.jsonLogDirectory, data.jsonConsoleLog, data.logPrefix),
            )
        }

        private fun logOutputConfig(directory: File?, console: Boolean?, prefix: String?): LogOutputConfig? {
            if (directory == null || console == null) return null
            return LogOutputConfig(directory = directory.path, console = console, logFilePrefix = prefix)
        }

        data class LoggingFromOpts(
            val debug: Boolean? = null,
            val textLogDirectory: File? = null,
            val textConsoleLog: Boolean? = null,
            val jsonLogDirectory: File? = null,
            val jsonConsoleLog: Boolean? = null,
            val logPrefix: String? = null,
            val commandName: String? = null,
            val component: String? = null,
        )
    }
}

fun <T> T?.nonNullElse(other: T?, orElse: (T, T) -> T): T? = when {
    this == null -> other
    other == null -> this
    else -> orElse(this, other)
}
