package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationSeverity
import io.specmatic.core.config.v3.components.runOptions.AsyncApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import io.specmatic.reporter.model.SpecType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProtocolConfigValidatorTest {
    @Test
    fun `invokes matching validator for non-empty async mock config`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val context = context(tempDir, validator)
        val specification = tempDir.resolve("events.yaml")

        val configuration = mapOf("servers" to listOf(mapOf("host" to "localhost")))
        val output = AsyncApiMockConfig()
            .withConfig(configuration)
            .validateForSpecFile(specification, context)

        assertThat(output).isEmpty()
        assertThat(validator.calls).containsExactly(
            Call(specification, configuration, "/dependencies/runOptions/asyncapi")
        )
    }

    @Test
    fun `invokes validator for empty async mock config so spec requirements are checked`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml")
        AsyncApiMockConfig().validateForSpecFile(specification, context(tempDir, validator))
        assertThat(validator.calls).containsExactly(Call(specification, emptyMap(), "/dependencies/runOptions/asyncapi"))
    }

    @Test
    fun `reports validator failures as configuration errors`(@TempDir tempDir: File) {
        val validator = RecordingValidator(failure = IllegalStateException("invalid broker configuration"))
        val output = AsyncApiMockConfig()
            .withConfig(mapOf("broker" to "localhost:9092"))
            .validateForSpecFile(tempDir.resolve("events.yaml"), context(tempDir, validator))

        assertThat(output).hasSize(1)
        assertThat(output[0].severity).isEqualTo(ConfigValidationSeverity.ERROR)
        assertThat(output[0].error).contains("invalid broker configuration")
        assertThat(output[0].instanceLocation).isEqualTo("/dependencies/runOptions/asyncapi")
    }

    private fun context(tempDir: File, validator: RecordingValidator): ValidationContext {
        return ValidationContext(
            location = "/dependencies/runOptions",
            protocolConfigValidators = listOf(validator),
            resolver = SpecmaticConfigV3Resolver(Components(), tempDir.toPath()),
        )
    }

    private data class Call(val specification: File, val configuration: Map<String, Any>, val location: String)
    private class RecordingValidator(private val failure: Throwable? = null) : ProtocolConfigValidator {
        val calls = mutableListOf<Call>()

        override fun supports(specType: SpecType, runOptionType: RunOptionType): Boolean {
            return specType == SpecType.ASYNCAPI && runOptionType == RunOptionType.MOCK
        }

        override fun validate(context: ValidationContext, specification: File, configuration: Map<String, Any>): List<ConfigValidationOutput> {
            calls += Call(specification, configuration, context.location)
            failure?.let { throw it }
            return emptyList()
        }
    }
}
