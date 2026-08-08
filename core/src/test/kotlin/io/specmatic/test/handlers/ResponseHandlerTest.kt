package io.specmatic.test.handlers

import io.specmatic.core.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResponseHandlerTest {
    @Test
    fun `Continue should preserve its one-argument constructor and response property`() {
        val response = HttpResponse(status = 200)

        val result = ResponseHandlingResult.Continue(response)

        assertThat(result.response).isSameAs(response)
        assertThat(result.responseForTestResultOverride).isNull()
    }
}
