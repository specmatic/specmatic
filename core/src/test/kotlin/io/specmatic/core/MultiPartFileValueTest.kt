package io.specmatic.core

import io.ktor.client.request.forms.formData
import io.ktor.http.HttpHeaders
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.value.StringValue
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class MultiPartFileValueTest {
    @Test
    fun `should generate a matching pattern`() {
        val pattern = MultiPartFileValue("some name", "@customers.csv", "text/csv", "gzip").inferType() as MultiPartFilePattern
        Assertions.assertThat(pattern.name).isEqualTo("some name")
        Assertions.assertThat(pattern.filename).isEqualTo(ExactValuePattern(StringValue("customers.csv")))
        Assertions.assertThat(pattern.contentType).isEqualTo("text/csv")
        Assertions.assertThat(pattern.contentEncoding).isEqualTo("gzip")
        Assertions.assertThat(pattern.content).isNull()
    }

    @Test
    fun `filename-only values infer a file-backed pattern`(@TempDir(cleanup = CleanupMode.ALWAYS) tempDir: File) {
        val expectedBytes = byteArrayOf(1, 2, 3)
        val exampleFile = tempDir.resolve("example.bin").apply { writeBytes(expectedBytes) }
        val pattern = MultiPartFileValue(
            name = "document",
            filename = exampleFile.canonicalPath,
            contentType = "application/octet-stream"
        ).inferType() as MultiPartFilePattern

        val result = pattern.matches(
            MultiPartFileValue(
                name = "document",
                filename = "uploaded.bin",
                contentType = "application/octet-stream",
                content = MultiPartContent(expectedBytes)
            ),
            Resolver()
        )

        assertThat(pattern.content).isNull()
        assertThat(result).isInstanceOf(Result.Success::class.java)
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
