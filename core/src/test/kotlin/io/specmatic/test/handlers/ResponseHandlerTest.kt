package io.specmatic.test.handlers

import io.specmatic.core.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseHandlerTest {
    @Test
    fun `Continue should preserve its one-argument JVM constructor and response property`() {
        val constructor = ResponseHandlingResult.Continue::class.java.getConstructor(HttpResponse::class.java)
        val response = HttpResponse(status = 200)

        val result = constructor.newInstance(response)

        assertThat(result.response).isSameAs(response)
        assertThat(result.responseForTestResultOverride).isNull()
    }
}
