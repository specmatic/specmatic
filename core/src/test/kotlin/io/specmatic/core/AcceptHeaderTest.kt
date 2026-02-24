package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AcceptHeaderTest {
    @Test
    fun `should match exact media type`() {
        assertThat(isResponseContentTypeAccepted("application/json", "application/json")).isTrue()
    }

    @Test
    fun `should support wildcard media type`() {
        assertThat(isResponseContentTypeAccepted("application/*", "application/json")).isTrue()
        assertThat(isResponseContentTypeAccepted("*/*", "application/json")).isTrue()
    }

    @Test
    fun `should support multiple accepted media types`() {
        assertThat(isResponseContentTypeAccepted("application/xml, application/json", "application/json")).isTrue()
    }

    @Test
    fun `should reject when none of accepted media types match`() {
        assertThat(isResponseContentTypeAccepted("application/xml, text/plain", "application/json")).isFalse()
    }

    @Test
    fun `should reject media type when q is zero`() {
        assertThat(isResponseContentTypeAccepted("application/json; q=0", "application/json")).isFalse()
    }
}
