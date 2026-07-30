package io.specmatic.core

import io.ktor.client.request.forms.formData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.utils.io.core.readBytes
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class MultiPartContentValueTest {
    @Nested
    inner class ConstructionAndInference {
        @Test
        fun `content value defaults filename content type and encoding to null`() {
            val value = MultiPartContentValue("data", StringValue("hello"))

            assertThat(value.filename).isNull()
            assertThat(value.contentType).isNull()
            assertThat(value.contentEncoding).isNull()
        }

        @Test
        fun `content value infers exact content and an absent filename expectation`() {
            val pattern = MultiPartContentValue("data", StringValue("hello")).inferType()

            assertThat(pattern.name).isEqualTo("data")
            assertThat(pattern.content).isEqualTo(ExactValuePattern(StringValue("hello")))
            assertThat(pattern.contentType).isNull()
            assertThat(pattern.filename).isEqualTo(FilenamePattern.Match(null))
        }

        @Test
        fun `file compatibility constructor retains path metadata and binary bytes`() {
            val value = MultiPartFileValue(
                name = "data",
                filename = "@fixtures/data.bin",
                contentType = "application/octet-stream",
                contentEncoding = "gzip",
                content = MultiPartContent(byteArrayOf(0, -1, 10)),
            )

            assertThat(value.filename).isEqualTo("fixtures/data.bin")
            assertThat(value.content).isEqualTo(BinaryValue(byteArrayOf(0, -1, 10)))
            assertThat(value.contentType).isEqualTo("application/octet-stream")
            assertThat(value.contentEncoding).isEqualTo("gzip")
        }

        @Test
        fun `concrete filename infers an exact pattern containing the full source path`() {
            val pattern = MultiPartContentValue(
                name = "data",
                content = BinaryValue(),
                filename = "fixtures/data.bin",
            ).inferType()

            assertThat(pattern.filename).isEqualTo(
                FilenamePattern.Match(ExactValuePattern(StringValue("fixtures/data.bin"))),
            )
        }

        @Test
        fun `string filename token infers a string pattern`() {
            val pattern = MultiPartContentValue(
                name = "data",
                content = BinaryValue(),
                filename = "(string)",
            ).inferType()

            assertThat(pattern.filename).isEqualTo(FilenamePattern.Match(StringPattern()))
        }

        @Test
        fun `inferred pattern retains content type and encoding`() {
            val pattern = MultiPartContentValue(
                name = "data",
                content = StringValue("hello"),
                specifiedContentType = "text/plain",
                contentEncoding = "gzip",
            ).inferType()

            assertThat(pattern.contentType).isEqualTo("text/plain")
            assertThat(pattern.contentEncoding).isEqualTo("gzip")
        }
    }

    @Nested
    inner class ExternalExampleParsing {
        @Test
        fun `content entry parses without a filename`() {
            val value = parsePart(
                """{"name":"data","content":"hello","contentType":"text/plain","contentEncoding":"gzip"}""",
            )

            assertThat(value.content).isEqualTo(StringValue("hello"))
            assertThat(value.filename).isNull()
            assertThat(value.contentType).isEqualTo("text/plain")
            assertThat(value.contentEncoding).isEqualTo("gzip")
        }

        @Test
        fun `filename entry retains its complete source path`() {
            val value = parsePart(
                """{"name":"data","filename":"@fixtures/data.bin","contentType":"application/octet-stream"}""",
            )

            assertThat(value.content).isEqualTo(BinaryValue())
            assertThat(value.filename).isEqualTo("fixtures/data.bin")
            assertThat(value.contentType).isEqualTo("application/octet-stream")
        }

        @Test
        fun `filename pattern remains a pattern and is not interpreted as a path`() {
            val value = parsePart("""{"name":"data","filename":"@(string)"}""")

            assertThat(value.filename).isEqualTo("(string)")
            assertThat(value.loadExternalFileContent()).isSameAs(value)
        }

        private fun parsePart(part: String): MultiPartContentValue {
            val request = parsedJSON(
                """{"method":"POST","path":"/","multipart-formdata":[$part]}""",
            ) as JSONObjectValue
            return requestFromJSON(request.jsonObject).multiPartFormData.single()
        }
    }

    @Nested
    inner class ExternalFileLoading {
        @Test
        fun `value without filename is unchanged`() {
            val value = MultiPartContentValue("data", StringValue("hello"))

            assertThat(value.loadExternalFileContent()).isSameAs(value)
        }

        @Test
        fun `filename pattern is not treated as a file path`() {
            val value = MultiPartContentValue(
                name = "data",
                content = BinaryValue(),
                filename = "(string)",
            )

            assertThat(value.loadExternalFileContent()).isSameAs(value)
        }

        @Test
        fun `absolute file loads arbitrary binary bytes and retains its path`(@TempDir tempDir: File) {
            val source = tempDir.resolve("data.bin").apply {
                writeBytes(byteArrayOf(0, -1, 10, 13))
            }
            val value = MultiPartContentValue(
                name = "data",
                content = BinaryValue(),
                filename = source.absolutePath,
            )

            val loaded = value.loadExternalFileContent()

            assertThat(loaded.filename).isEqualTo(source.absolutePath)
            assertThat(loaded.content).isEqualTo(BinaryValue(byteArrayOf(0, -1, 10, 13)))
        }

        @Test
        fun `nested relative path survives repeated loading`(@TempDir tempDir: File) {
            val source = tempDir.resolve("fixtures/data.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(0, -1, 10, 13))
            }
            val relativePath = source.canonicalFile.relativeTo(File(".").canonicalFile).path
            val value = MultiPartContentValue(
                name = "data",
                content = BinaryValue(),
                filename = relativePath,
            )

            val loadedTwice = value.loadExternalFileContent().loadExternalFileContent()

            assertThat(loadedTwice.filename).isEqualTo(relativePath)
            assertThat(loadedTwice.content).isEqualTo(BinaryValue(byteArrayOf(0, -1, 10, 13)))
        }

        @Test
        fun `missing file retains its path and uses generated fallback content`(@TempDir tempDir: File) {
            val missingPath = tempDir.resolve("missing.bin").absolutePath
            val value = MultiPartContentValue(
                name = "data",
                content = BinaryValue(),
                filename = missingPath,
            )

            val loaded = value.loadExternalFileContent()

            assertThat(loaded.filename).isEqualTo(missingPath)
            assertThat(loaded.content).isInstanceOf(BinaryValue::class.java)
            assertThat((loaded.content as BinaryValue).byteArray).isNotEmpty()
        }
    }

    @Nested
    inner class JsonSerialization {
        @Test
        fun `content value serializes content without a filename`() {
            val json = MultiPartContentValue("data", StringValue("hello")).toJSONObject().jsonObject

            assertThat(json).containsEntry("name", StringValue("data"))
            assertThat(json).containsEntry("content", StringValue("hello"))
            assertThat(json).doesNotContainKey("filename")
        }

        @Test
        fun `file value serializes the original source path with at prefix`() {
            val json = MultiPartContentValue(
                name = "data",
                content = BinaryValue(byteArrayOf(0, -1)),
                filename = "fixtures/data.bin",
            ).toJSONObject().jsonObject

            assertThat(json).containsEntry("filename", StringValue("@fixtures/data.bin"))
            assertThat(json).doesNotContainKey("content")
        }

        @Test
        fun `content type and encoding are serialized only when present`() {
            val withMetadata = MultiPartContentValue(
                name = "data",
                content = StringValue("hello"),
                specifiedContentType = "text/plain",
                contentEncoding = "gzip",
            ).toJSONObject().jsonObject
            val withoutMetadata = MultiPartContentValue(
                name = "data",
                content = StringValue("hello"),
            ).toJSONObject().jsonObject

            assertThat(withMetadata).containsEntry("contentType", StringValue("text/plain"))
            assertThat(withMetadata).containsEntry("contentEncoding", StringValue("gzip"))
            assertThat(withoutMetadata).doesNotContainKeys("contentType", "contentEncoding")
        }
    }

    @Nested
    inner class MultipartSerialization {
        @Test
        fun `value without filename produces no filename header`() {
            val part = partFrom(
                MultiPartContentValue(
                    name = "data",
                    content = StringValue("hello"),
                    specifiedContentType = "text/plain",
                ),
            )

            assertThat(part.headers[HttpHeaders.ContentDisposition]).doesNotContain("filename")
            assertThat(part.contentType.toString()).isEqualTo("text/plain")
        }

        @Test
        fun `source path is reduced to basename in the multipart filename header`() {
            val part = partFrom(
                MultiPartContentValue(
                    name = "data",
                    content = BinaryValue(byteArrayOf(0, -1, 10)),
                    specifiedContentType = "application/octet-stream",
                    filename = "fixtures/data.bin",
                ),
            )

            assertThat(part.headers.getAll(HttpHeaders.ContentDisposition))
                .anySatisfy { assertThat(it).contains("filename=data.bin") }
                .allSatisfy { assertThat(it).doesNotContain("fixtures") }
            assertThat(part.headers["Content-Transfer-Encoding"]).isEqualTo("binary")
        }

        @Test
        fun `multipart serialization preserves arbitrary binary bytes`() {
            val part = partFrom(
                MultiPartContentValue(
                    name = "data",
                    content = BinaryValue(byteArrayOf(0, -1, 10, 13)),
                    filename = "data.bin",
                ),
            ) as PartData.BinaryItem

            assertThat(part.provider().readBytes()).containsExactly(0, -1, 10, 13)
        }

        @Test
        fun `display value uses basename and hides binary content`() {
            val display = MultiPartContentValue(
                name = "data",
                content = BinaryValue(byteArrayOf(0, -1)),
                specifiedContentType = "application/octet-stream",
                filename = "fixtures/data.bin",
            ).toDisplayableValue()

            assertThat(display).contains("""filename="data.bin"""")
            assertThat(display).doesNotContain("fixtures")
            assertThat(display).contains("(Binary content not shown)")
        }

        private fun partFrom(value: MultiPartContentValue): PartData =
            formData { value.addTo(this) }.single()
    }
}
