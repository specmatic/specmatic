package io.specmatic.core.log

import io.specmatic.core.config.LogOutputConfig
import io.specmatic.core.config.LogRedactionConfig
import io.specmatic.core.config.LoggingConfiguration
import io.specmatic.core.value.BooleanValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class RunDiagnosticsLogTest {
    @Test
    fun `run diagnostics should include runtime and logging configuration summary`() {
        val message =
            RunDiagnosticsLog(
                LoggingConfiguration(
                    json = LogOutputConfig(directory = "logs/json", console = true, logFilePrefix = "specmatic-json"),
                    text = LogOutputConfig(directory = "logs/text", console = false, logFilePrefix = "specmatic-text"),
                    redaction = LogRedactionConfig(headers = setOf("X-Secret"), jsonKeys = setOf("pin"), mask = "<hidden>"),
                ),
            )

        val json = message.toJSONObject()

        assertThat(message.eventType()).isEqualTo("run_diagnostics")
        assertThat(json.getString("specmaticVersion")).isNotBlank()
        assertThat(json.getString("javaVersion")).isNotBlank()
        assertThat(json.getString("osName")).isNotBlank()
        assertThat(json.getString("workingDirectory")).isNotBlank()

        assertThat(json.findFirstChildByPath("logging.redactionEnabled")).isEqualTo(BooleanValue(true))
        assertThat(json.findFirstChildByPath("logging.text.configured")).isEqualTo(BooleanValue(true))
        assertThat(json.findFirstChildByPath("logging.text.console")).isEqualTo(BooleanValue(false))
        assertThat(json.findFirstChildByPath("logging.text.directory")).isEqualTo(StringValue("logs/text"))
        assertThat(json.findFirstChildByPath("logging.json.configured")).isEqualTo(BooleanValue(true))
        assertThat(json.findFirstChildByPath("logging.json.console")).isEqualTo(BooleanValue(true))
        assertThat(json.findFirstChildByPath("logging.json.directory")).isEqualTo(StringValue("logs/json"))
        assertThat(json.findFirstChildByPath("logging.mask")).isEqualTo(StringValue("<hidden>"))
        assertThat(json.findFirstChildByPath("logging.redactedHeaders")).isNotNull()
        assertThat(json.findFirstChildByPath("logging.redactedJsonKeys")).isNotNull()
    }
}
