package io.specmatic.core.log

import io.specmatic.core.config.LogRedactionConfig
import io.specmatic.core.config.LogOutputConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class LoggingSetupTest {
    @Test
    fun `configure logging initializes run context`() {
        configureLogging(
            opts =
                LoggingConfiguration.Companion.LoggingFromOpts(
                    commandName = "test-command",
                    component = "test-component",
                ),
            source = LoggingConfigSource.FromConfig(LoggingConfiguration.default()),
        )

        val context = LogContext.current()
        assertThat(context.command).isEqualTo("test-command")
        assertThat(context.component).isEqualTo("test-component")
        assertThat(context.runId).isNotBlank()
    }

    @Test
    fun `configure logging initializes redaction settings from config`() {
        configureLogging(
            source =
                LoggingConfigSource.FromConfig(
                    LoggingConfiguration(
                        redaction =
                            LogRedactionConfig(
                                enabled = true,
                                headers = setOf("X-Secret"),
                                jsonKeys = setOf("pin"),
                                mask = "<hidden>",
                            ),
                    ),
                ),
        )

        val settings = LogRedactionContext.current()
        assertThat(settings.enabled).isTrue()
        assertThat(settings.headersToRedact).contains("authorization")
        assertThat(settings.headersToRedact).contains("x-secret")
        assertThat(settings.jsonKeysToRedact).contains("password")
        assertThat(settings.jsonKeysToRedact).contains("pin")
        assertThat(settings.mask).isEqualTo("<hidden>")
    }

    @Test
    fun `configure logging emits run diagnostics in json mode`() {
        val output =
            captureStandardOutput {
                configureLogging(
                    source =
                        LoggingConfigSource.FromConfig(
                            LoggingConfiguration(
                                text = LogOutputConfig(directory = null, console = false),
                                json = LogOutputConfig(directory = null, console = true),
                            ),
                        ),
                )
            }

        val line = output.first.trim()
        val logLine = parsedJSON(line) as JSONObjectValue
        assertThat(logLine.getString("eventType")).isEqualTo("run_diagnostics")
        assertThat(logLine.getString("level")).isEqualTo("INFO")
        assertThat(logLine.findFirstChildByPath("payload.specmaticVersion")).isNotNull()
        assertThat(logLine.findFirstChildByPath("payload.logging.level")).isNotNull()
    }
}
