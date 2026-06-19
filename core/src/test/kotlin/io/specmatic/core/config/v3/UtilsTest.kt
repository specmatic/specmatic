package io.specmatic.core.config.v3

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UtilsTest {
    @Test
    fun `ServerOrigin withPath should append path to base url`() {
        val origin = ServerOrigin.from("http://example.com")
        assertThat(origin.withPath("/api/v1")).isEqualTo(ServerOrigin.from("http://example.com/api/v1"))
    }

    @Test
    fun `ServerOrigin withPath should build base url from network address`() {
        val origin = ServerOrigin.NetworkAddress(host = "localhost", port = 8080)
        assertThat(origin.withPath("api/v1")).isEqualTo(ServerOrigin.from("http://localhost:8080/api/v1"))
    }
}
