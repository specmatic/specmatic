package application

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path
import kotlin.io.path.readText

class ConfigCommandUpgradeTest {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    @Test
    fun `upgrades specmatic v2 config file to expected output`(@TempDir tempDir: Path) {
        assertUpgrade(
            inputResource = "/configFiles/specmatic_v2.json",
            expectedResource = "/configFiles/specmatic_v2.upgraded.yaml",
            tempDir = tempDir,
        )
    }

    private fun assertUpgrade(inputResource: String, expectedResource: String, tempDir: Path) {
        val inputFile = resourceFile(inputResource)
        val expectedFile = resourceFile(expectedResource)
        val outputFile = tempDir.resolve(expectedFile.fileName.toString())

        val exitCode = CommandLine(ConfigCommand.Upgrade()).execute("--input", inputFile.toString(), "--output", outputFile.toString())
        assertThat(exitCode).isEqualTo(0)

        val upgraded = mapper.readTree(outputFile.readText())
        val expected = mapper.readTree(expectedFile.readText())
        assertThat(upgraded)
            .describedAs("Upgraded config mismatch for '$inputResource'")
            .isEqualTo(expected)
    }

    private fun resourceFile(resourcePath: String): Path {
        return requireNotNull(javaClass.getResource(resourcePath)) { "Missing test resource: $resourcePath" }.toURI().let(Path::of)
    }
}
