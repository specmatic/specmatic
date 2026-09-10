package application

import io.specmatic.core.config.validation.ConfigValidationMetadata
import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationResult
import io.specmatic.core.config.validation.ConfigValidationSeverity
import io.specmatic.core.utilities.Flags
import io.specmatic.core.utilities.Flags.Companion.CONFIG_FILE_PATH
import io.specmatic.core.utilities.SystemExit
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class ConfigCommandValidateTest {
    @Nested
    inner class Registration {
        @Test
        fun `registers validate alongside upgrade`() {
            assertThat(CommandLine(ConfigCommand()).subcommands.keys)
                .containsExactlyInAnyOrder("upgrade", "validate")
        }
    }

    @Nested
    inner class CommandExecution {
        @Test
        fun `validates the default configuration path`(@TempDir tempDir: File) {
            val configFile = tempDir.resolve("specmatic.yaml").apply { writeText("version: 3\n") }
            val (output, exitCode) = Flags.using(CONFIG_FILE_PATH to configFile.path) {
                captureOutput { CommandLine(ConfigCommand()).execute("validate") }
            }

            assertThat(exitCode).isZero()
            assertThat(output).isEqualTo("Configuration is valid: specmatic.yaml")
        }

        @Test
        fun `validates an explicit input and returns a text diagnostic`(@TempDir tempDir: File) {
            val configFile = tempDir.resolve("custom.yaml").apply {
                writeText("version: 2\nreport: invalid\n")
            }

            val (output, exitCode) = captureOutput {
                CommandLine(ConfigCommand()).execute("validate", "--input", configFile.path)
            }

            assertThat(exitCode).withFailMessage(output).isEqualTo(1)
            assertThat(output).isEqualTo("""
            Configuration is invalid: custom.yaml

            custom.yaml:2:9
            2 |     report: invalid
              |             ^

              string found, object expected
              Reporting configuration — Report types and API-coverage thresholds.

            1 error
            """.trimIndent())
        }

        @Test
        fun `serializes validation errors as a detailed json schema output`(@TempDir tempDir: File) {
            val configFile = tempDir.resolve("invalid.yaml").apply {
                writeText("version: 2\nreport: invalid\n")
            }

            val (output, exitCode) = captureOutput {
                CommandLine(ConfigCommand()).execute(
                    "validate", "--input", configFile.path, "--format", "json",
                )
            }

            assertThat(exitCode).isEqualTo(1)
            assertJsonOutput(output, $$"""
            {
              "valid": false,
              "keywordLocation": "",
              "instanceLocation": "",
              "errors": [
                {
                  "valid": false,
                  "keywordLocation": "/properties/report/$ref",
                  "instanceLocation": "/report",
                  "absoluteKeywordLocation": "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ReportConfigurationDetails",
                  "error": "string found, object expected",
                  "severity": "ERROR",
                  "metadata": {
                    "title": "Reporting configuration",
                    "keyword": "$ref",
                    "description": "Report types and API-coverage thresholds."
                  }
                }
              ]
            }""".trimIndent())
        }

        @Test
        fun `serializes a valid result as a detailed json schema output`(@TempDir tempDir: File) {
            val configFile = tempDir.resolve("valid.yaml").apply {
                writeText("version: 3\n")
            }

            val (output, exitCode) = captureOutput {
                CommandLine(ConfigCommand()).execute(
                    "validate", "--input", configFile.path, "--format", "json",
                )
            }

            assertThat(exitCode).isZero()
            assertThat(output).isEqualTo("""
            {
              "valid": true,
              "keywordLocation": "",
              "instanceLocation": "",
              "severity": "INFO"
            }""".trimIndent())
        }

        @Test
        fun `renders a valid result without an error severity in text output`(@TempDir tempDir: File) {
            val configFile = tempDir.resolve("valid.yaml").apply {
                writeText("version: 3\n")
            }

            val (output, exitCode) = captureOutput {
                CommandLine(ConfigCommand()).execute(
                    "validate", "--input", configFile.path,
                )
            }

            assertThat(exitCode).isZero()
            assertThat(output).isEqualTo("Configuration is valid: valid.yaml")
        }

        @Test
        fun `reports an unreadable input without a stack trace`(@TempDir tempDir: File) {
            val missingFile = tempDir.resolve("missing.yaml")
            val (output, exitCode) = captureOutput {
                CommandLine(ConfigCommand()).execute(
                    "validate", "--input", missingFile.path,
                )
            }

            assertThat(exitCode).isEqualTo(1)
            assertThat(output).endsWith("1 error")
            assertThat(output).doesNotContain("Exception")
            assertThat(output).contains(
                "Configuration is invalid: missing.yaml",
                "missing.yaml:/",
                "Could not read ${missingFile.path}: ${missingFile.path}",
            )
        }

        @Test
        fun `explicit input is validated even when the default configuration is broken`(@TempDir tempDir: File) {
            val defaultFile = tempDir.resolve("default.yaml").apply { writeText("version: [") }
            val explicitFile = tempDir.resolve("explicit.yaml").apply { writeText("version: 3\n") }
            val (output, exitCode) = Flags.using(CONFIG_FILE_PATH to defaultFile.path) {
                captureOutput {
                    try {
                        SystemExit.throwOnExit {
                            SpecmaticApplication.main(arrayOf("config", "validate", "--input", explicitFile.path))
                        }
                        -1
                    } catch (exception: io.specmatic.core.utilities.SystemExitException) {
                        exception.code
                    }
                }
            }

            assertThat(exitCode).isZero()
            assertThat(output.lineSequence().last()).isEqualTo("Configuration is valid: explicit.yaml")
        }
    }

    @Nested
    inner class TextRenderer {
        private val renderer = ConfigValidationTextRenderer()

        @Test
        fun `renders multiple issues with metadata and severity`() {
            val source = """
            version: 3
            logPrefix: specmatic
            banana: true
            """.trimIndent()

            val outputs = listOf(
                ConfigValidationOutput(
                    valid = false,
                    error = "Deprecated property.",
                    instanceLocation = "/logPrefix",
                    keywordLocation = "/properties/logPrefix",
                    severity = ConfigValidationSeverity.WARNING,
                    metadata = ConfigValidationMetadata(
                        deprecated = true,
                        title = "Log prefix",
                        deprecationMessage = "use 'logFilePrefix' instead.",
                    ),
                ),
                ConfigValidationOutput(
                    valid = false,
                    instanceLocation = "/banana",
                    error = "Unknown property 'banana'.",
                    keywordLocation = "/additionalProperties",
                    severity = ConfigValidationSeverity.ERROR,
                ),
            )

            assertThat(renderer.render("specmatic.yaml", source, ConfigValidationResult.Invalid(null, outputs))).isEqualTo("""
            Configuration is invalid: specmatic.yaml

            specmatic.yaml:2:1
            2 |     logPrefix: specmatic
              |     ^

              Deprecated property.
              Deprecated: use 'logFilePrefix' instead.


            specmatic.yaml:3:1
            3 |     banana: true
              |     ^

              Unknown property 'banana'.

            1 error, 1 warning
            """.trimIndent())
        }

        @Test
        fun `supports flat schema output when requested`(@TempDir tempDir: File) {
            val configFile = tempDir.resolve("invalid.yaml").apply {
                writeText("version: 2\nreport: invalid\n")
            }

            val (output, exitCode) = captureOutput {
                CommandLine(ConfigCommand()).execute(
                    "validate", "--input", configFile.path, "--format", "json", "--schema-output", "list",
                )
            }

            assertThat(exitCode).isEqualTo(1)
            assertJsonOutput(output, $$"""
            {
              "valid": false,
              "keywordLocation": "",
              "instanceLocation": "",
              "errors": [
                {
                  "valid": false,
                  "keywordLocation": "/properties/report/$ref",
                  "instanceLocation": "/report",
                  "absoluteKeywordLocation": "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ReportConfigurationDetails",
                  "error": "string found, object expected",
                  "severity": "ERROR",
                  "metadata": {
                    "title": "Reporting configuration",
                    "keyword": "$ref",
                    "description": "Report types and API-coverage thresholds."
                  }
                }
              ]
            }""".trimIndent())
        }

        @Test
        fun `falls back to the instance location when source location is unavailable`() {
            val output = ConfigValidationOutput(
                valid = false,
                keywordLocation = "/required",
                instanceLocation = "/components",
                error = "Configuration has an invalid structure.",
            )

            assertThat(renderer.render("specmatic.json", "", ConfigValidationResult.Invalid(null, listOf(output)))).isEqualTo("""
            Configuration is invalid: specmatic.json

            specmatic.json:/components
              Configuration has an invalid structure.

            1 error
            """.trimIndent())
        }

        @Test
        fun `uses the nearest source location when instance location is unavailable`() {
            val source = """
            version: 3
            components:
              services: {}
            """.trimIndent()
            val output = ConfigValidationOutput(
                valid = false,
                keywordLocation = "/required",
                instanceLocation = "/components/services/missing",
                error = "Configuration has an invalid structure.",
            )

            val rendered = renderer.render("specmatic.yaml", source, ConfigValidationResult.Invalid(null, listOf(output)))

            assertThat(rendered)
                .contains("specmatic.yaml:3:")
                .contains("3 |       services: {}")
                .doesNotContain("specmatic.yaml:/components/services/missing")
        }

        @Test
        fun `renders nested validation details without flattening the hierarchy`() {
            val source = "version: 2\nreport: invalid\n"
            val output = ConfigValidationOutput(
                valid = false,
                keywordLocation = "/oneOf",
                instanceLocation = "/report",
                error = "Value does not match any supported report shape.",
                details = listOf(
                    element = ConfigValidationOutput(
                        valid = false,
                        keywordLocation = "/oneOf/0",
                        instanceLocation = "/report",
                        error = "string found, object expected",
                        details = listOf(
                            element = ConfigValidationOutput(
                                valid = false,
                                instanceLocation = "/report",
                                keywordLocation = "/oneOf/0/type",
                                error = "report must be an object",
                            ),
                        ),
                    ),
                ),
            )

            assertThat(renderer.render("config.yaml", source, ConfigValidationResult.Invalid(null, listOf(output)))).isEqualTo("""
            Configuration is invalid: config.yaml

            config.yaml:2:9
            2 |     report: invalid
              |             ^

              Value does not match any supported report shape.

            Validation details:
              config.yaml:2:9
              2 |     report: invalid
                |             ^

                string found, object expected

              Validation details:
                config.yaml:2:9
                2 |     report: invalid
                  |             ^

                  report must be an object

            1 error
            """.trimIndent())
        }
    }

    private fun assertJsonOutput(actual: String, expected: String) {
        assertThat(Json.parseToJsonElement(actual)).isEqualTo(Json.parseToJsonElement(expected))
    }

    private fun captureOutput(block: () -> Int): Pair<String, Int> {
        val originalOut = System.out
        val originalErr = System.err
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes))
        System.setErr(PrintStream(bytes))
        return try {
            val exitCode = block()
            String(bytes.toByteArray()).trimEnd() to exitCode
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
