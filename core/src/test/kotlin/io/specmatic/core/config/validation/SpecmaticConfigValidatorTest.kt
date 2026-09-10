package io.specmatic.core.config.validation

import io.specmatic.core.config.SpecmaticConfigVersion
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SpecmaticConfigValidatorTest {
    private val validator = SpecmaticConfigValidator()
    private val outputJson = Json { encodeDefaults = true }

    @Nested
    inner class Binding {
        @ParameterizedTest(name = "{0}")
        @MethodSource("io.specmatic.core.config.validation.SpecmaticConfigValidatorTest#validConfigurationCases")
        fun `accepts configurations that bind successfully`(testCase: ValidConfigurationCase) {
            assertThat(validator.validate(testCase.configuration))
                .isEqualTo(ConfigValidationResult.Valid(testCase.version))
        }
    }

    @Nested
    inner class InputParsing {
        @Test
        fun `returns standard output for a parse failure`() {
            assertThat(validator.validate("version: [")).isEqualTo(
                ConfigValidationResult.Invalid(
                    version = null,
                    output = semanticOutput("/", "Configuration could not be parsed as YAML or JSON.")
                )
            )
        }

        @Test
        fun `returns standard output for a non literal version`() {
            assertThat(validator.validate($$"version: '${VERSION:2}'")).isEqualTo(
                ConfigValidationResult.Invalid(
                    version = null,
                    output = semanticOutput("/version", "Configuration validation supports only a literal integer version 1, 2, or 3.")
                )
            )
        }
    }

    @Nested
    inner class SchemaPipeline {
        @Test
        fun `returns standard output for a schema failure and does not bind it`() {
            val result = validator.validate("version: 2\nreport: invalid")
            assertThat(result).isEqualTo(
                ConfigValidationResult.Invalid(
                    version = SpecmaticConfigVersion.VERSION_2,
                    output = invalidOutput(
                        detail = ConfigValidationOutput(
                            valid = false,
                            instanceLocation = "/report",
                            error = "string found, object expected",
                            keywordLocation = $$"/properties/report/$ref",
                            absoluteKeywordLocation = "https://specmatic.io/internal-schema/config-v2-resolved.schema.json#/definitions/ReportConfigurationDetails",
                            metadata = ConfigValidationMetadata(
                                keyword = $$"$ref",
                                title = "Reporting configuration",
                                description = "Report types and API-coverage thresholds.",
                            ),
                        )
                    )
                )
            )
        }

        @Test
        fun `returns schema validation before typed semantic validation`() {
            val result = validator.validate("""
            version: 3
            systemUnderTest: {}
            """.trimIndent())

            assertThat(result).isEqualTo(
                ConfigValidationResult.Invalid(
                    version = SpecmaticConfigVersion.VERSION_3,
                    output = invalidOutput(
                        detail = ConfigValidationOutput(
                            valid = false,
                            instanceLocation = "/systemUnderTest",
                            error = "required property 'service' not found",
                            keywordLocation = $$"/properties/systemUnderTest/$ref",
                            absoluteKeywordLocation = "https://specmatic.io/internal-schema/config-v3-resolved.schema.json#/definitions/SystemUnderTestServiceDefinition",
                            metadata = ConfigValidationMetadata(
                                keyword = $$"$ref",
                                title = "System under test",
                                description = "The single service Specmatic exercises as the system under test.",
                            ),
                        )
                    )
                )
            )
        }
    }

    @Nested
    inner class OutputSerialization {
        @Test
        fun `serializes validation output with kotlinx serialization`() {
            val metadata = ConfigValidationMetadata(
                title = "Report",
                keyword = "required",
                deprecated = true,
                description = "Report configuration",
                deprecationMessage = "Replace with replacement.",
            )

            val output = listOf(
                element = ConfigValidationOutput(
                    valid = false,
                    error = "invalid",
                    metadata = metadata,
                    keywordLocation = "",
                    instanceLocation = "/report",
                    absoluteKeywordLocation = "urn:specmatic:config:v2",
                )
            )

            assertThat(output.single().metadata).isEqualTo(metadata)
            assertThat(outputJson.encodeToString(output)).isEqualTo(
                """[{"valid":false,"error":"invalid","keywordLocation":"","instanceLocation":"/report","absoluteKeywordLocation":"urn:specmatic:config:v2","severity":"ERROR","metadata":{"title":"Report","keyword":"required","description":"Report configuration","deprecated":true,"deprecationMessage":"Replace with replacement."}}]"""
            )
        }
    }

    companion object {
        data class ValidConfigurationCase(val name: String, val version: SpecmaticConfigVersion, val configuration: String) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun validConfigurationCases() = listOf(
            ValidConfigurationCase(
                configuration = "version: 1",
                version = SpecmaticConfigVersion.VERSION_1,
                name = "V1 binding without schema validation",
            ),
            ValidConfigurationCase(
                version = SpecmaticConfigVersion.VERSION_2,
                name = "V2 after production template resolution",
                configuration = $$"version: 2\nhooks:\n  preSpecmaticRequestProcessor: '${HOOK:script}'",
            ),
            ValidConfigurationCase(
                name = "minimal V3",
                configuration = "version: 3",
                version = SpecmaticConfigVersion.VERSION_3,
            ),
        )
    }

    private fun invalidOutput(detail: ConfigValidationOutput) = listOf(detail)
    private fun semanticOutput(instanceLocation: String, message: String) = listOf(
        element = ConfigValidationOutput(
            valid = false,
            error = message,
            keywordLocation = "",
            instanceLocation = instanceLocation,
        )
    )
}
