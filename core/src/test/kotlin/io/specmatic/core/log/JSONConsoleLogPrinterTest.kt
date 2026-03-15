package io.specmatic.core.log

import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class JSONConsoleLogPrinterTest {
    @Test
    fun `prints JSON to console`() {
        LogContext.startRun(command = "json-console-test", component = "test")
        val output = captureStandardOutput {
            JSONConsoleLogPrinter.print(StringLog("hello"))
        }

        val logLine = parsedJSON(output.first) as JSONObjectValue
        assertThat(logLine.getString("command")).isEqualTo("json-console-test")
        assertThat(logLine.getString("component")).isEqualTo("test")
        assertThat(logLine.getString("level")).isEqualTo("INFO")
        assertThat(logLine.getString("eventType")).isEqualTo("text")
        assertThat(logLine.getString("thread")).isNotBlank()
        assertThat(logLine.getString("timestamp")).isNotBlank()
        assertThat(logLine.getString("runId")).isNotBlank()
        assertThat(logLine.findFirstChildByPath("payload.message")).isEqualTo(StringValue("hello"))
    }

}
