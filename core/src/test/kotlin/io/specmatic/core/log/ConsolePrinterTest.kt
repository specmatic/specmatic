package io.specmatic.core.log

import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

internal class ConsolePrinterTest {
    @Test
    fun `should print to standard output`() {
        val output: Pair<String, Unit> = captureStandardOutput {
            ConsolePrinter.print(StringLog("hello"))
        }

        Assertions.assertThat(output.first).startsWith("hello")
    }
}
