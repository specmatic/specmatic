package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class StringLogTest {
    @Test
    fun `should infer warning level from warning prefix`() {
        assertThat(StringLog("WARNING: check input").level()).isEqualTo("WARNING")
    }

    @Test
    fun `should infer error level from error prefix`() {
        assertThat(StringLog("ERROR: failure").level()).isEqualTo("ERROR")
    }

    @Test
    fun `should default to info level`() {
        assertThat(StringLog("Hello world").level()).isEqualTo("INFO")
    }
}
