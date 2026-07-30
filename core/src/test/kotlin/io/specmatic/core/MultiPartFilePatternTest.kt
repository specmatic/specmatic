package io.specmatic.core

import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.BinaryPattern
import io.specmatic.core.pattern.ExactValuePattern
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class MultiPartFilePatternTest {
    private val valueWithoutFilename = MultiPartContentValue("data", StringValue("hello"))
    private val valueWithFilename = valueWithoutFilename.copy(filename = "data.txt")

    @Test
    fun `specification pattern ignores filename presence`() {
        val pattern = MultiPartContentPattern("data", StringPattern(), filename = FilenamePattern.Ignore)

        assertThat(pattern.matches(valueWithoutFilename, Resolver())).isInstanceOf(Success::class.java)
        assertThat(pattern.matches(valueWithFilename, Resolver())).isInstanceOf(Success::class.java)
    }

    @Test
    fun `example without filename rejects a filename`() {
        val pattern = MultiPartContentPattern("data", StringPattern(), filename = FilenamePattern.Match(null))

        assertThat(pattern.matches(valueWithoutFilename, Resolver())).isInstanceOf(Success::class.java)
        assertThat(pattern.matches(valueWithFilename, Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `example with exact filename requires the same filename`() {
        val pattern = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Match(ExactValuePattern(StringValue("data.txt"))),
        )

        assertThat(pattern.matches(valueWithFilename, Resolver())).isInstanceOf(Success::class.java)
        assertThat(pattern.matches(valueWithoutFilename, Resolver())).isInstanceOf(Failure::class.java)
        assertThat(pattern.matches(valueWithFilename.copy(filename = "other.txt"), Resolver()))
            .isInstanceOf(Failure::class.java)
    }

    @Test
    fun `string filename pattern requires any filename`() {
        val pattern = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Match(StringPattern()),
        )

        assertThat(pattern.matches(valueWithFilename, Resolver())).isInstanceOf(Success::class.java)
        assertThat(pattern.matches(valueWithoutFilename, Resolver())).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `specification generated value has no filename`() {
        val generated = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Ignore,
        ).generate(Resolver())

        assertThat(generated.filename).isNull()
    }

    @Test
    fun `example without filename generates a value without filename`() {
        val examplePattern = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Ignore,
        ).withExample(valueWithoutFilename)

        assertThat(examplePattern.filename).isEqualTo(FilenamePattern.Match(null))
        assertThat(examplePattern.generate(Resolver()).filename).isNull()
    }

    @Test
    fun `example file path is retained while its content is loaded`(@TempDir tempDir: File) {
        val exampleFile = tempDir.resolve("data.txt").apply { writeText("hello") }
        val example = MultiPartContentValue(
            name = "data",
            content = BinaryValue(),
            filename = exampleFile.path,
        )

        val examplePattern = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Ignore,
        ).withExample(example)
        val generated = examplePattern.generate(Resolver())

        assertThat(generated.filename).isEqualTo(exampleFile.path)
        assertThat(generated.content).isEqualTo(BinaryValue("hello".encodeToByteArray()))
    }

    @Test
    fun `relative example file path survives repeated content loading`(@TempDir tempDir: File) {
        val exampleFile = tempDir.resolve("fixtures/data.txt").apply {
            parentFile.mkdirs()
            writeText("hello")
        }
        val relativePath = exampleFile.canonicalFile.relativeTo(File(".").canonicalFile).path
        val example = MultiPartContentValue(
            name = "data",
            content = BinaryValue(),
            filename = relativePath,
        )

        val loadedTwice = example.loadExternalFileContent().loadExternalFileContent()
        val examplePattern = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Ignore,
        ).withExample(example)
        val generated = examplePattern.generate(Resolver())

        assertThat(loadedTwice.filename).isEqualTo(relativePath)
        assertThat(loadedTwice.content).isEqualTo(BinaryValue("hello".encodeToByteArray()))
        assertThat(generated.filename).isEqualTo(relativePath)
        assertThat(
            examplePattern.matches(generated.copy(filename = exampleFile.name), Resolver())
        ).isInstanceOf(Success::class.java)
    }

    @Test
    fun `example filename pattern requires and generates a filename without replacing spec content`() {
        val example = MultiPartContentValue(
            name = "data",
            content = BinaryValue(),
            filename = "(string)",
        )

        val examplePattern = MultiPartContentPattern(
            "data",
            StringPattern(),
            filename = FilenamePattern.Ignore,
        ).withExample(example)
        val generated = examplePattern.generate(Resolver())

        assertThat(examplePattern.content).isEqualTo(StringPattern())
        assertThat(generated.filename).isNotBlank()
        assertThat(generated.content).isInstanceOf(StringValue::class.java)
    }

    @Test
    fun `exact file pattern generates file content while retaining its source path`(@TempDir tempDir: File) {
        val source = tempDir.resolve("data.txt").apply { writeText("hello") }
        val pattern = MultiPartContentPattern(
            name = "data",
            content = BinaryPattern(),
            filename = FilenamePattern.Match(ExactValuePattern(StringValue(source.absolutePath))),
        )

        val generated = pattern.generate(Resolver())

        assertThat(generated.content).isEqualTo(BinaryValue("hello".encodeToByteArray()))
        assertThat(generated.filename).isEqualTo(source.absolutePath)
    }

    @Test
    fun `exact file pattern matches file content and basename filename`(@TempDir tempDir: File) {
        val source = tempDir.resolve("data.txt").apply { writeText("hello") }
        val pattern = MultiPartContentPattern(
            name = "data",
            content = BinaryPattern(),
            filename = FilenamePattern.Match(ExactValuePattern(StringValue(source.absolutePath))),
        )
        val value = MultiPartContentValue(
            name = "data",
            content = BinaryValue("hello".encodeToByteArray()),
            filename = "data.txt",
        )

        assertThat(pattern.matches(value, Resolver())).isInstanceOf(Success::class.java)
    }
}
