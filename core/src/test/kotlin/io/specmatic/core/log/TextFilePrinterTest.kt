package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TextFilePrinterTest {
    @Test
    fun `prints text to a file`() {
        var printedText = ""
        var count = 0

        TextFilePrinter(object: LogFile {
            override fun appendText(text: String) {
                printedText = text
                count += 1
            }
        }).print(StringLog("test"))

        assertThat(printedText.trim()).isEqualTo("test")
        assertThat(count).isOne
    }
}
