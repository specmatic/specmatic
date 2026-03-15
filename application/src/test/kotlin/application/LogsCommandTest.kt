package application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class LogsCommandTest {
    @Test
    fun `logs export should bundle files into zip`(@TempDir tempDir: File) {
        val sourceDir = tempDir.resolve("logs").also { it.mkdirs() }
        sourceDir.resolve("app.log").writeText("hello")
        sourceDir.resolve("nested").also { it.mkdirs() }.resolve("events-json.log").writeText("""{"a":1}""")

        val outputZip = tempDir.resolve("bundle.zip")

        val command =
            LogsCommand.Export().also {
                it.sourceDirectory = sourceDir
                it.outputPath = outputZip
            }

        val exitCode = command.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(outputZip).exists()

        ZipFile(outputZip).use { zip ->
            assertThat(zip.getEntry("manifest.txt")).isNotNull()
            assertThat(zip.getEntry("app.log")).isNotNull()
            assertThat(zip.getEntry("nested/events-json.log")).isNotNull()
        }
    }

    @Test
    fun `logs export should fail when source directory is missing`(@TempDir tempDir: File) {
        val command =
            LogsCommand.Export().also {
                it.sourceDirectory = tempDir.resolve("missing")
            }

        val exitCode = command.call()
        assertThat(exitCode).isEqualTo(1)
    }
}
