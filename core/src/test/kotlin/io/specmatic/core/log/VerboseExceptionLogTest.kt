package io.specmatic.core.log

import io.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class VerboseExceptionLogTest {
    val log = try {
        throw ContractException("test")
    } catch(e: Throwable) {
        VerboseExceptionLog(e, "msg")
    }

    @Test
    fun `verbose exception log text`() {
        assertThat(log.toLogString().trim().lines().first()).isEqualTo("msg: test")
        assertThat(log.toLogString().trim()).contains("ContractException")
    }

    @Test
    fun `verbose exception log json`() {
        log.toJSONObject().apply {
            assertThat(this.getString("className")).isEqualTo("io.specmatic.core.pattern.ContractException")
            assertThat(this.getString("cause")).isEqualTo("test")
            assertThat(this.getString("message")).isEqualTo("msg")
            assertThat(this.getString("stackTrace")).contains("ContractException")
            assertThat(this.getString("errorCode")).isEqualTo("ContractException")
            assertThat(this.getString("errorCategory")).isEqualTo("CONTRACT")
        }
    }
}
