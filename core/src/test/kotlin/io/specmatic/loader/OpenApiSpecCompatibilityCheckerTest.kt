package io.specmatic.loader

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OpenApiSpecCompatibilityCheckerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `identifies compatible example without specmatic config`() {
        val example = tempDir.resolve("example.json").toFile().apply {
            writeText("""{"http-request": {}}""")
        }
        val regularJson = tempDir.resolve("config.json").toFile().apply {
            writeText("""{"setting": true}""")
        }

        val checker = OpenApiSpecCompatibilityChecker()

        assertThat(checker.isCompatibleExample(example)).isTrue()
        assertThat(checker.isCompatibleExample(regularJson)).isFalse()
    }
}
