package io.specmatic.core

import io.ktor.client.request.forms.formData
import io.ktor.http.HttpHeaders
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue

internal class MultiPartFileValueTest {
    @Test
    fun `should generate a matching pattern`() {
        val pattern = MultiPartFileValue("some name", "@customers.csv", "text/csv", "gzip").inferType() as MultiPartFilePattern
        Assertions.assertThat(pattern.name).isEqualTo("some name")
        Assertions.assertThat(pattern.filename).isEqualTo(ExactValuePattern(StringValue("customers.csv")))
        Assertions.assertThat(pattern.contentType).isEqualTo("text/csv")
        Assertions.assertThat(pattern.contentEncoding).isEqualTo("gzip")
    }

    @Test
    fun `inferred file pattern preserves filename and exact content independently`() {
        val bytes = byteArrayOf(1, 2, 3)

        val pattern = MultiPartFileValue(
            name = "document",
            filename = "example.pdf",
            contentType = "application/pdf",
            content = MultiPartContent(bytes)
        ).inferType() as MultiPartFilePattern

        assertThat(pattern.filename).isEqualTo(ExactValuePattern(StringValue("example.pdf")))
        assertThat(pattern.content).isEqualTo(ExactValuePattern(BinaryValue(bytes)))
    }

    @Test
    fun `serialization omits filename when it is absent`() {
        val value = MultiPartFileValue(
            name = "document",
            filename = "",
            contentType = "application/octet-stream",
            content = MultiPartContent(byteArrayOf(1, 2, 3))
        )

        val part = formData { value.addTo(this) }.single()

        assertThat(part.headers[HttpHeaders.ContentDisposition]).doesNotContain("filename=")
    }
}
