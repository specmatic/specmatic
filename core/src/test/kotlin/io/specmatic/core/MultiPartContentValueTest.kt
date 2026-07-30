package io.specmatic.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.value.StringValue

internal class MultiPartContentValueTest {
    @Test
    fun `should generate a matching pattern`() {
        val pattern = MultiPartContentValue("some name", StringValue("data")).inferType() as MultiPartContentPattern
        assertThat(pattern.name).isEqualTo("some name")
        assertThat(pattern.content).isEqualTo(ExactValuePattern(StringValue("data")))
        assertThat(pattern.contentType).isNull()
    }

    @Test
    fun `should generate a matching pattern with contentType`() {
        val pattern = MultiPartContentValue("some name", StringValue("data"), specifiedContentType = "application/json").inferType() as MultiPartContentPattern
        assertThat(pattern.name).isEqualTo("some name")
        assertThat(pattern.content).isEqualTo(ExactValuePattern(StringValue("data")))
        assertThat(pattern.contentType).isEqualTo("application/json")
    }
}
