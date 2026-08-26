package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.Resolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NoSecuritySchemeTest {
    @Nested
    inner class FixValueTests {
        @Test
        fun `should preserve a request without security`() {
            val request = HttpRequest(headers = mapOf("X-Request-ID" to "original"))
            assertThat(NoSecurityScheme().fixValue(request, Resolver())).isEqualTo(request)
        }
    }
}
