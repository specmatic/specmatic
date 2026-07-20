package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.HttpResponse
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub

internal class RowTest {
    @Test
    fun `returns a plain value if present`() {
        val row = Row(listOf("name"), listOf("Jane"))
        assertThat(row.getField("name")).isEqualTo("Jane")
    }

    @Test
    fun `responseBody returns body from scenario stub response`() {
        val responseBody = StringValue("hello")
        val row = Row(scenarioStub = ScenarioStub(response = HttpResponse(200, responseBody)))

        assertThat(row.responseBody()).isEqualTo(responseBody)
    }

    @Test
    fun `responseBody returns null when scenario stub is absent`() {
        val row = Row()

        assertThat(row.responseBody()).isNull()
    }
}
