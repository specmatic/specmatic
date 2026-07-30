package io.specmatic.core

import io.specmatic.core.pattern.BinaryPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.Row
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.isOptional
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class MultiPartContentPatternTest {
    private val resolver = Resolver()
    private val valueWithoutFilename = value()
    private val valueWithFilename = value(filename = "data.txt")

    @Nested
    inner class FilenameMatching {
        @Test
        fun `ignore accepts a missing or present filename`() {
            val pattern = pattern(filename = FilenamePattern.Ignore)

            assertThat(pattern.matches(valueWithoutFilename, resolver).isSuccess()).isTrue()
            assertThat(pattern.matches(valueWithFilename, resolver).isSuccess()).isTrue()
        }

        @Test
        fun `match without a pattern requires the filename to be absent`() {
            val pattern = pattern(filename = FilenamePattern.Match(null))

            assertThat(pattern.matches(valueWithoutFilename, resolver).isSuccess()).isTrue()
            assertThat(pattern.matches(valueWithFilename, resolver).isSuccess()).isFalse()
        }

        @Test
        fun `exact filename requires the same filename`() {
            val pattern = pattern(
                filename = FilenamePattern.Match(ExactValuePattern(StringValue("data.txt"))),
            )

            assertThat(pattern.matches(valueWithoutFilename, resolver).isSuccess()).isFalse()
            assertThat(pattern.matches(valueWithFilename, resolver).isSuccess()).isTrue()
            assertThat(pattern.matches(value(filename = "other.txt"), resolver).isSuccess()).isFalse()
        }

        @Test
        fun `string filename pattern requires any non-null filename`() {
            val pattern = pattern(filename = FilenamePattern.Match(StringPattern()))

            assertThat(pattern.matches(valueWithoutFilename, resolver).isSuccess()).isFalse()
            assertThat(pattern.matches(valueWithFilename, resolver).isSuccess()).isTrue()
            assertThat(pattern.matches(value(filename = "other.txt"), resolver).isSuccess()).isTrue()
        }

        @Test
        fun `exact source path is projected to its basename for matching`(@TempDir tempDir: File) {
            val source = tempDir.resolve("fixtures/data.txt").apply {
                parentFile.mkdirs()
                writeText("hello")
            }
            val pattern = MultiPartContentPattern(
                name = "data",
                content = BinaryPattern(),
                filename = FilenamePattern.Match(ExactValuePattern(StringValue(source.absolutePath))),
            )

            assertThat(
                pattern.matches(
                    value(content = BinaryValue("hello".encodeToByteArray()), filename = "data.txt"),
                    resolver,
                ).isSuccess(),
            ).isTrue()
        }

        @Test
        fun `filename mismatch has a filename breadcrumb`() {
            val pattern = pattern(
                filename = FilenamePattern.Match(ExactValuePattern(StringValue("data.txt"))),
            )

            assertThat(pattern.matches(value(filename = "other.txt"), resolver).reportString())
                .contains("filename")
        }
    }

    @Nested
    inner class ContentMatching {
        @Test
        fun `string content is parsed before matching a typed pattern`() {
            val pattern = MultiPartContentPattern("data", NumberPattern())

            assertThat(pattern.matches(value(content = StringValue("10")), resolver).isSuccess()).isTrue()
            assertThat(pattern.matches(value(content = StringValue("not-a-number")), resolver).isSuccess()).isFalse()
        }

        @Test
        fun `json content matches an object pattern`() {
            val pattern = MultiPartContentPattern(
                "data",
                JSONObjectPattern(mapOf("name" to StringPattern())),
            )

            assertThat(
                pattern.matches(value(content = parsedJSONObject("""{"name":"Jane"}""")), resolver).isSuccess(),
            ).isTrue()
            assertThat(
                pattern.matches(value(content = parsedJSONObject("""{"id":"10"}""")), resolver).isSuccess(),
            ).isFalse()
        }

        @Test
        fun `exact binary content compares bytes without converting them to text`() {
            val expected = BinaryValue(byteArrayOf(0, -1, 10, 13))
            val pattern = MultiPartContentPattern("data", ExactValuePattern(expected))

            assertThat(pattern.matches(value(content = expected), resolver).isSuccess()).isTrue()
            assertThat(
                pattern.matches(value(content = BinaryValue(byteArrayOf(0, -1, 10, 14))), resolver).isSuccess(),
            ).isFalse()
        }

        @Test
        fun `mock mode accepts a pattern token in content`() {
            val pattern = MultiPartContentPattern("data", NumberPattern())

            assertThat(
                pattern.matches(value(content = StringValue("(number)")), Resolver(mockMode = true)).isSuccess(),
            ).isTrue()
        }
    }

    @Nested
    inner class MetadataMatching {
        @Test
        fun `missing expected content type ignores the actual content type`() {
            val pattern = pattern(contentType = null)

            assertThat(pattern.matches(value(contentType = "application/json"), resolver).isSuccess()).isTrue()
        }

        @Test
        fun `missing actual content type is treated as text plain`() {
            val textPattern = pattern(contentType = "text/plain")
            val jsonPattern = pattern(contentType = "application/json")

            assertThat(textPattern.matches(value(contentType = null), resolver).isSuccess()).isTrue()
            assertThat(jsonPattern.matches(value(contentType = null), resolver).isSuccess()).isFalse()
        }

        @Test
        fun `content type matching accepts compatible parameters and rejects another media type`() {
            val pattern = pattern(contentType = "application/json")

            assertThat(
                pattern.matches(value(contentType = "application/json; charset=UTF-8"), resolver).isSuccess(),
            ).isTrue()
            assertThat(pattern.matches(value(contentType = "text/plain"), resolver).isSuccess()).isFalse()
        }

        @Test
        fun `one of the declared content types may match`() {
            val pattern = pattern(contentType = "application/json, text/plain")

            assertThat(pattern.matches(value(contentType = "text/plain"), resolver).isSuccess()).isTrue()
        }

        @Test
        fun `missing expected content encoding ignores the actual encoding`() {
            val pattern = pattern(contentEncoding = null)

            assertThat(pattern.matches(value(contentEncoding = "gzip"), resolver).isSuccess()).isTrue()
        }

        @Test
        fun `expected content encoding requires an exact value`() {
            val pattern = pattern(contentEncoding = "gzip")

            assertThat(pattern.matches(value(contentEncoding = "gzip"), resolver).isSuccess()).isTrue()
            assertThat(pattern.matches(value(contentEncoding = null), resolver).isSuccess()).isFalse()
            assertThat(pattern.matches(value(contentEncoding = "identity"), resolver).isSuccess()).isFalse()
        }
    }

    @Nested
    inner class ExampleSpecialization {
        @Test
        fun `example without filename specializes the pattern to require no filename`() {
            val specialized = pattern().withExample(valueWithoutFilename)

            assertThat(specialized.filename).isEqualTo(FilenamePattern.Match(null))
            assertThat(specialized.generate(resolver).filename).isNull()
        }

        @Test
        fun `concrete file example loads exact bytes and retains its source path`(@TempDir tempDir: File) {
            val source = tempDir.resolve("fixtures/data.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(0, -1, 10))
            }
            val specialized = pattern().withExample(
                value(content = BinaryValue(), filename = source.path),
            )
            val generated = specialized.generate(resolver)

            assertThat(generated.filename).isEqualTo(source.path)
            assertThat(generated.content).isEqualTo(BinaryValue(byteArrayOf(0, -1, 10)))
            assertThat(
                specialized.matches(generated.copy(filename = source.name), resolver).isSuccess(),
            ).isTrue()
        }

        @Test
        fun `string filename example constrains the filename but preserves the specification content pattern`() {
            val specification = MultiPartContentPattern("data", NumberPattern())
            val specialized = specification.withExample(
                value(content = BinaryValue(), filename = "(string)"),
            )

            assertThat(specialized.content).isEqualTo(NumberPattern())
            assertThat(specialized.filename).isEqualTo(FilenamePattern.Match(StringPattern()))
            assertThat(specialized.generate(resolver).filename).isNotBlank()
            assertThat(specialized.generate(resolver).content).isInstanceOf(NumberValue::class.java)
        }

        @Test
        fun `example metadata overrides only values present in the example`() {
            val specification = pattern(contentType = "text/plain", contentEncoding = "identity")

            val specialized = specification.withExample(
                value(contentType = "application/json", contentEncoding = null),
            )

            assertThat(specialized.contentType).isEqualTo("application/json")
            assertThat(specialized.contentEncoding).isEqualTo("identity")
        }

        @Test
        fun `request example is used directly when specializing from a row`() {
            val example = value(content = StringValue("from-example"), filename = null)
            val row = Row(
                requestExample = HttpRequest(multiPartFormData = listOf(example)),
            )

            val specialized = pattern().newBasedOn(row, resolver).single()!!

            assertThat(specialized.content).isEqualTo(ExactValuePattern(StringValue("from-example")))
            assertThat(specialized.filename).isEqualTo(FilenamePattern.Match(null))
        }
    }

    @Nested
    inner class GenerationAndOptionality {
        @Test
        fun `specification pattern generates no filename`() {
            assertThat(pattern(filename = FilenamePattern.Ignore).generate(resolver).filename).isNull()
        }

        @Test
        fun `match without a filename pattern generates no filename`() {
            assertThat(pattern(filename = FilenamePattern.Match(null)).generate(resolver).filename).isNull()
        }

        @Test
        fun `exact file pattern generates exact bytes while retaining the source path`(@TempDir tempDir: File) {
            val source = tempDir.resolve("data.bin").apply { writeBytes(byteArrayOf(0, -1, 10)) }
            val pattern = MultiPartContentPattern(
                name = "data",
                content = BinaryPattern(),
                filename = FilenamePattern.Match(ExactValuePattern(StringValue(source.absolutePath))),
            )

            val generated = pattern.generate(resolver)

            assertThat(generated.filename).isEqualTo(source.absolutePath)
            assertThat(generated.content).isEqualTo(BinaryValue(byteArrayOf(0, -1, 10)))
        }

        @Test
        fun `string filename pattern generates a non-null filename`() {
            val generated = pattern(
                filename = FilenamePattern.Match(StringPattern()),
            ).generate(resolver)

            assertThat(generated.filename).isNotBlank()
        }

        @Test
        fun `row content specializes the content without changing filename policy`() {
            val originalFilename = FilenamePattern.Match(ExactValuePattern(StringValue("data.txt")))
            val specialized = MultiPartContentPattern(
                "data",
                NumberPattern(),
                filename = originalFilename,
            ).newBasedOn(Row(listOf("data"), listOf("10")), resolver).single()!!

            assertThat(specialized.content).isEqualTo(ExactValuePattern(NumberValue(10)))
            assertThat(specialized.filename).isEqualTo(originalFilename)
        }

        @Test
        fun `row filename specializes an ignored spec filename to an exact example filename`() {
            val specialized = pattern(filename = FilenamePattern.Ignore)
                .newBasedOn(
                    Row(
                        columnNames = listOf("data_filename"),
                        values = listOf("fixtures/data.bin"),
                    ),
                    resolver,
                ).single()!!

            assertThat(specialized.filename).isEqualTo(
                FilenamePattern.Match(ExactValuePattern(StringValue("data.bin"))),
            )
        }

        @Test
        fun `optional part produces absent and present variants without examples`() {
            val variants = MultiPartContentPattern("data?", StringPattern())
                .newBasedOn(Row(), resolver)
                .toList()

            assertThat(variants).hasSize(2)
            assertThat(variants.first()).isNull()
            assertThat(variants.last()!!.name).isEqualTo("data")
            assertThat(isOptional(variants.last()!!.name)).isFalse()
        }

        @Test
        fun `optional part absent from a request example produces only the absent variant`() {
            val variants = MultiPartContentPattern("data?", StringPattern())
                .newBasedOn(Row(requestExample = HttpRequest()), resolver)
                .toList()

            assertThat(variants).containsExactly(null)
        }
    }

    private fun pattern(
        contentType: String? = null,
        contentEncoding: String? = null,
        filename: FilenamePattern = FilenamePattern.Ignore,
    ) = MultiPartContentPattern(
        name = "data",
        content = StringPattern(),
        contentType = contentType,
        contentEncoding = contentEncoding,
        filename = filename,
    )

    private fun value(
        content: io.specmatic.core.value.Value = StringValue("hello"),
        contentType: String? = null,
        contentEncoding: String? = null,
        filename: String? = null,
    ) = MultiPartContentValue(
        name = "data",
        content = content,
        specifiedContentType = contentType,
        contentEncoding = contentEncoding,
        filename = filename,
    )
}
