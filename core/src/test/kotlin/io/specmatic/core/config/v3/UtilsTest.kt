package io.specmatic.core.config.v3

import io.specmatic.reporter.model.SpecType

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UtilsTest {
    @Test
    fun `detects large AsyncAPI and Arazzo specifications`(@org.junit.jupiter.api.io.TempDir directory: java.io.File) {
        val file = directory.resolve("large.yaml")
        listOf("asyncapi" to SpecType.ASYNCAPI, "arazzo" to SpecType.ARAZZO).forEach { (key, type) ->
            file.writeText("x-padding: " + "x".repeat(5 * 1024 * 1024) + "\n$key: 1.0.0\n")
            assertThat(determineSpecTypeFor(file)).containsExactly(type)
        }
    }

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
