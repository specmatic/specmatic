package io.specmatic.core.log

import io.specmatic.core.value.JSONObjectValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class NewLineLogMessageTest {
    @Test
    fun `returns an empty string`() {
        assertThat(NewLineLogMessage.toLogString()).isEqualTo("")
    }

    @Test
    fun `returns an empty JSON object`() {
        assertThat(NewLineLogMessage.toJSONObject()).isEqualTo(JSONObjectValue())
    }
}
