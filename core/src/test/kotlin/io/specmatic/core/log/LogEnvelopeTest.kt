package io.specmatic.core.log

import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class LogEnvelopeTest {
    @Test
    fun `envelopes include contextual metadata and payload`() {
        LogContext.startRun(command = "envelope-test", component = "core-test", runId = "run-123")

        val envelope = LogEnvelope.from(StringLog("hello"))

        assertThat(envelope.getString("runId")).isEqualTo("run-123")
        assertThat(envelope.getString("command")).isEqualTo("envelope-test")
        assertThat(envelope.getString("component")).isEqualTo("core-test")
        assertThat(envelope.getString("level")).isEqualTo("INFO")
        assertThat(envelope.getString("eventType")).isEqualTo("text")
        assertThat(envelope.getString("timestamp")).isNotBlank()
        assertThat(envelope.getString("thread")).isNotBlank()
        assertThat(envelope.findFirstChildByPath("payload.message")).isEqualTo(StringValue("hello"))
    }

    @Test
    fun `exception logs are marked as error level`() {
        val envelope = LogEnvelope.from(NonVerboseExceptionLog(Exception("boom"), "failed"))

        assertThat(envelope.getString("level")).isEqualTo("ERROR")
        assertThat(envelope.getString("eventType")).isEqualTo("exception")
        assertThat(envelope.findFirstChildByPath("payload.message")).isEqualTo(StringValue("failed"))
    }

    @Test
    fun `envelope includes correlation id when set`() {
        LogCorrelationContext.start("corr-123")
        try {
            val envelope = LogEnvelope.from(StringLog("hello"))
            assertThat(envelope.getString("correlationId")).isEqualTo("corr-123")
        } finally {
            LogCorrelationContext.clear()
        }
    }
}
