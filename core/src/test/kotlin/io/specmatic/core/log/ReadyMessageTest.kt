package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class ReadyMessageTest {

    @Test
    fun `ready message gets printed once only`() {
        val messages = mutableListOf<LogMessage>()

        val printer = object: LogPrinter {
            override fun print(msg: LogMessage, indentation: String) {
                messages.add(msg)
            }
        }

        ReadyMessage(StringLog("Test")).apply {
            printLogString(printer)
            printLogString(printer)
        }

        assertThat(messages.size).isOne
        assertThat(messages.first().toLogString()).isEqualTo("Test")
    }
}
