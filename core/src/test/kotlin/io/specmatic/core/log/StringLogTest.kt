package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class StringLogTest {
    private val logMessage = StringLog("test")

    @Test
    fun `plain string log`() {
        assertThat(logMessage.toLogString().trim()).isEqualTo("test")
    }

    @Test
    fun `JSON log`() {
        val jsonLog = logMessage.toJSONObject()
        assertThat(jsonLog.getString("message")).isEqualTo("test")
    }
}
