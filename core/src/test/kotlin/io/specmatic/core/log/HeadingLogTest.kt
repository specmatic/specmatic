package io.specmatic.core.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class HeadingLogTest {
    @Test
    fun `heading log prints heading with prefix`() {
        assertThat(HeadingLog("test").toLogString()).isEqualTo("# test")
    }
}
