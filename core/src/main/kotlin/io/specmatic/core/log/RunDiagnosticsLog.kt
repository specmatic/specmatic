package io.specmatic.core.log

import io.specmatic.core.config.LogOutputConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.specmatic.core.VersionInfo
import java.io.File
import java.lang.management.ManagementFactory

class RunDiagnosticsLog(
    private val config: LoggingConfiguration,
) : LogMessage {
    override fun eventType(): String = "run_diagnostics"

    override fun toJSONObject(): JSONObjectValue {
        val diagnostics =
            mutableMapOf<String, Value>(
                "specmaticVersion" to StringValue(VersionInfo.describe()),
                "javaVersion" to StringValue(System.getProperty("java.version").orEmpty()),
                "javaVendor" to StringValue(System.getProperty("java.vendor").orEmpty()),
                "osName" to StringValue(System.getProperty("os.name").orEmpty()),
                "osVersion" to StringValue(System.getProperty("os.version").orEmpty()),
                "osArch" to StringValue(System.getProperty("os.arch").orEmpty()),
                "processId" to NumberValue(currentProcessId()),
                "workingDirectory" to StringValue(resolveWorkingDirectory()),
                "logging" to loggingSummary(config),
            )

        val runtimeName = ManagementFactory.getRuntimeMXBean().name
        if (runtimeName.isNotBlank()) {
            diagnostics["jvmRuntime"] = StringValue(runtimeName)
        }

        return JSONObjectValue(diagnostics)
    }

    override fun toLogString(): String {
        val loggingLevel = config.levelOrDefault().value()
        return "Run diagnostics: Specmatic ${VersionInfo.describe()}, Java ${System.getProperty("java.version").orEmpty()}, logging level $loggingLevel"
    }

    private fun loggingSummary(config: LoggingConfiguration): JSONObjectValue {
        val redaction = config.redactionConfigurationOrDefault()

        return JSONObjectValue(
            mapOf(
                "level" to StringValue(config.levelOrDefault().value()),
                "text" to outputSummary(config.text),
                "json" to outputSummary(config.json),
                "redactionEnabled" to BooleanValue(redaction.isEnabledOrDefault()),
                "redactedHeaders" to JSONArrayValue(redaction.headersOrDefault().map(::StringValue)),
                "redactedJsonKeys" to JSONArrayValue(redaction.jsonKeysOrDefault().map(::StringValue)),
                "mask" to StringValue(redaction.maskOrDefault()),
            ),
        )
    }

    private fun outputSummary(outputConfig: LogOutputConfig?): JSONObjectValue {
        if (outputConfig == null) {
            return JSONObjectValue(
                mapOf(
                    "configured" to BooleanValue(false),
                ),
            )
        }

        return JSONObjectValue(
            mapOf(
                "configured" to BooleanValue(true),
                "console" to BooleanValue(outputConfig.isConsoleLoggingEnabled(default = false)),
                "directory" to StringValue(outputConfig.directory.orEmpty()),
                "logFilePrefix" to StringValue(outputConfig.getLogFilePrefixOrDefault()),
            ),
        )
    }

    private fun currentProcessId(): Number {
        return try {
            ProcessHandle.current().pid()
        } catch (_: Throwable) {
            -1
        }
    }

    private fun resolveWorkingDirectory(): String {
        return runCatching { File("").absoluteFile.canonicalPath }.getOrDefault("")
    }
}
