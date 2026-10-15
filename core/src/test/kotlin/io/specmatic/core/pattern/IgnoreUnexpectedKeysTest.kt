package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class IgnoreUnexpectedKeysTest {
    @Test
    fun `always returns null`() {
        assertThat(IgnoreUnexpectedKeys.validate(emptyMap(), emptyMap())).isNull()
    }
}
