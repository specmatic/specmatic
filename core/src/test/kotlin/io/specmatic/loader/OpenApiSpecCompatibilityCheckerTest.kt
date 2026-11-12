package io.specmatic.loader

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class OpenApiSpecCompatibilityCheckerTest {
    @TempDir
    lateinit var tempDir: Path

    @Nested
    @DisplayName("JSON examples")
    inner class JsonExamples {
        @ParameterizedTest
        @ValueSource(strings = ["", "\uFEFF"])
        fun `identifies compatible example with or without BOM`(bom: String) {
            val example = tempDir.resolve("example.json").toFile().apply {
                writeText("$bom{\"http-request\": {}}")
            }

            val regularJson = tempDir.resolve("config.json").toFile().apply {
                writeText("""{"setting": true}""")
            }

            val checker = OpenApiSpecCompatibilityChecker()
            assertThat(checker.isCompatibleExample(example)).isTrue()
            assertThat(checker.isCompatibleExample(regularJson)).isFalse()
        }
    }
}
