package io.specmatic.core.log

import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class JSONConsoleLogPrinterTest {
    @Test
    fun `prints JSON to console`() {
        val output = captureStandardOutput {
            JSONConsoleLogPrinter.print(StringLog("hello"))
        }

        assertThat(output.first).isEqualTo("""
        {
            "message": "hello"
        }""".trimIndent())
    }

}
