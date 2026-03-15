package io.specmatic.core.log

import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

internal class JSONFilePrinterTest {
    @Test
    fun `prints json to a file`() {
        LogContext.startRun(command = "json-file-test", component = "test")
        var printedText = ""
        var count = 0

        JSONFilePrinter(object: LogFile {
            override fun appendText(text: String) {
                printedText = text
                count += 1
            }
        }).print(StringLog("test"))

        val logLine = parsedJSON(printedText.trim()) as JSONObjectValue
        Assertions.assertThat(logLine.getString("command")).isEqualTo("json-file-test")
        Assertions.assertThat(logLine.getString("component")).isEqualTo("test")
        Assertions.assertThat(logLine.getString("level")).isEqualTo("INFO")
        Assertions.assertThat(logLine.getString("eventType")).isEqualTo("text")
        Assertions.assertThat(logLine.findFirstChildByPath("payload.message")).isEqualTo(StringValue("test"))
        Assertions.assertThat(count).isOne
    }
}
