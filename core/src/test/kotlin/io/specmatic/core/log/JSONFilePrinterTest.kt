package io.specmatic.core.log

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

internal class JSONFilePrinterTest {
    @Test
    fun `prints json to a file`() {
        var printedText = """{
    "message": "test"
}"""
        var count = 0

        JSONFilePrinter(object: LogFile {
            override fun appendText(text: String) {
                printedText = text
                count += 1
            }
        }).print(StringLog("test"))

        Assertions.assertThat(printedText.trim()).isEqualTo("""{
    "message": "test"
}""")
        Assertions.assertThat(count).isOne
    }
}
