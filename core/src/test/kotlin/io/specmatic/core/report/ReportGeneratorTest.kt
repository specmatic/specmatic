package io.specmatic.core.report

import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.Flags.Companion.using
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReportGeneratorTest {
    @Test
    fun `specmaticConfigAsMap returns empty map when config file is empty`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply { writeText("") }

        val configAsMap = using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            ReportGenerator.specmaticConfigAsMap()
        }

        assertThat(configAsMap).isEmpty()
    }

    @Test
    fun `specmaticConfigAsMap returns map for valid yaml config`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText(
                """
                version: 2
                test:
                  generativeTests: true
                stub:
                  delayInMilliseconds: 123
                """.trimIndent()
            )
        }

        val configAsMap = using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            ReportGenerator.specmaticConfigAsMap()
        }

        assertThat(configAsMap["version"]).isEqualTo(2)
        assertThat(configAsMap["test"]).isInstanceOf(Map::class.java)
        assertThat((configAsMap["test"] as Map<*, *>)["generativeTests"]).isEqualTo(true)
        assertThat(configAsMap["stub"]).isInstanceOf(Map::class.java)
        assertThat((configAsMap["stub"] as Map<*, *>)["delayInMilliseconds"]).isEqualTo(123)
    }

    @Test
    fun `specmaticConfigAsMap resolves templates in config values`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply {
            writeText(
                """
                version: 2
                pipeline:
                  organization: "${'{'}PIPELINE_ORG:default-org}"
                """.trimIndent()
            )
        }

        val configAsMap = using(
            CONFIG_FILE_PATH to configFile.canonicalPath,
            "PIPELINE_ORG" to "acme"
        ) {
            ReportGenerator.specmaticConfigAsMap()
        }

        assertThat(configAsMap["pipeline"]).isInstanceOf(Map::class.java)
        assertThat((configAsMap["pipeline"] as Map<*, *>)["organization"]).isEqualTo("acme")
    }

    @Test
    fun `specmaticConfigAsMap returns empty map when config is invalid`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("specmatic.yaml").apply { writeText(": : :") }

        val configAsMap = using(CONFIG_FILE_PATH to configFile.canonicalPath) {
            ReportGenerator.specmaticConfigAsMap()
        }

        assertThat(configAsMap).isEmpty()
    }
}
