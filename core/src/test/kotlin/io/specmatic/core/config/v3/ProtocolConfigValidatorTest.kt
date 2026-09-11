package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationSeverity
import io.specmatic.core.config.v3.components.runOptions.AsyncApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import io.specmatic.core.config.v3.components.runOptions.RunOptionsSpecifications
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
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
        val definition = SpecificationDefinition.StringValue(specification.path)
        val output = AsyncApiMockConfig()
            .withConfig(configuration)
            .validateForSpecFile(specification, definition, context)

        assertThat(output).isEmpty()
        assertThat(validator.calls).containsExactly(
            Call(specification, configuration, "/dependencies/runOptions/asyncapi")
        )
    }

    @Test
    fun `invokes validator for empty async mock config so spec requirements are checked`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml")
        val definition = SpecificationDefinition.StringValue(specification.path)
        AsyncApiMockConfig().validateForSpecFile(specification, definition, context(tempDir, validator))
        assertThat(validator.calls).containsExactly(Call(specification, emptyMap(), "/dependencies/runOptions/asyncapi"))
    }

    @Test
    fun `passes matching async mock specification override and its location to the validator`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val definition = SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "kafka", path = specification.path))

        val baseConfiguration = mapOf("inMemoryBroker" to mapOf("port" to 9091))
        val overrideConfiguration = mapOf("inMemoryBroker" to mapOf("port" to 9092))
        val runOptions = AsyncApiMockConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "other-kafka").withConfig(
                        mapOf("inMemoryBroker" to mapOf("port" to 9090)),
                    ),
                ),
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "kafka").withConfig(overrideConfiguration),
                ),
            ),
        ).withConfig(baseConfiguration)

        runOptions.validateForSpecFile(specification, definition, context(tempDir, validator))
        assertThat(validator.calls).containsExactly(
            Call(
                specification = specification,
                configuration = overrideConfiguration,
                location = "/dependencies/runOptions/asyncapi/specs/1",
            ),
        )
    }

    @Test
    fun `uses top-level async mock configuration and location when matching specification override is empty`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val definition = SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "kafka", path = specification.path))

        val baseConfiguration = mapOf("inMemoryBroker" to mapOf("port" to 9091))
        val runOptions = AsyncApiMockConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "kafka"),
                ),
            ),
        ).withConfig(baseConfiguration)

        runOptions.validateForSpecFile(specification, definition, context(tempDir, validator))
        assertThat(validator.calls).containsExactly(
            Call(
                specification = specification,
                configuration = baseConfiguration,
                location = "/dependencies/runOptions/asyncapi",
            ),
        )
    }

    @Test
    fun `uses default async mock run options when service run options are omitted`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val definition = Definition(
            Definition.Value(
                source = RefOrValue.Value(source),
                specs = listOf(SpecificationDefinition.StringValue(specification.name)),
            )
        )

        val service = CommonServiceConfig<MockRunOptions, MockSettings>(definitions = listOf(definition))
        MockServiceConfig(
            services = listOf(MockServiceConfig.Value(RefOrValue.Value(service))),
        ).validate(
            ValidationContext(
                location = "/dependencies",
                protocolConfigValidators = listOf(validator),
                resolver = SpecmaticConfigV3Resolver(Components(), tempDir.toPath()),
            )
        )

        assertThat(validator.calls).containsExactly(
            Call(specification.canonicalFile, emptyMap(), "/dependencies/services/0/service/runOptions/asyncapi")
        )
    }

    @Test
    fun `keeps inline service ref sibling value location when validating run options`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val reference = RefOrValue.Reference(
            ref = "#/components/services/kafkaService",
            extra = mapOf("runOptions" to emptyMap<String, Any>()),
        )

        MockServiceConfig(services = listOf(MockServiceConfig.Value(reference))).validate(
            context = ValidationContext(
                location = "/dependencies",
                protocolConfigValidators = listOf(validator),
                resolver = object : RefOrValueResolver {
                    override fun resolveRef(reference: String): Any = mapOf(
                        "definitions" to listOf(
                            mapOf(
                                "definition" to mapOf(
                                    "source" to mapOf(
                                        "filesystem" to mapOf("directory" to tempDir.canonicalPath),
                                    ),
                                    "specs" to listOf(specification.name),
                                ),
                            ),
                        ),
                    )
                },
            )
        )

        assertThat(validator.calls).containsExactly(
            Call(
                configuration = emptyMap(),
                specification = specification.canonicalFile,
                location = "/dependencies/services/0/service/runOptions/asyncapi"
            )
        )
    }

    @Test
    fun `uses the referenced run options location when an inline service sibling is a reference`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val serviceReference = "#/components/services/kafkaService"
        val runOptionsReference = "#/components/runOptions/kafka"
        val reference = RefOrValue.Reference(
            ref = serviceReference,
            extra = mapOf(
                "runOptions" to mapOf($$"$ref" to runOptionsReference),
            ),
        )

        MockServiceConfig(services = listOf(MockServiceConfig.Value(reference))).validate(
            context = contextFor(
                tempDir = tempDir,
                validator = validator,
                specification = specification,
                serviceReference = serviceReference,
                runOptionsReference = runOptionsReference,
            )
        )

        assertThat(validator.calls).containsExactly(
            Call(
                specification = specification.canonicalFile,
                location = "/components/runOptions/kafka/asyncapi",
                configuration = mapOf("inMemoryBroker" to mapOf("port" to 9091)),
            )
        )
    }

    @Test
    fun `keeps inline location for a sibling on a referenced run options value`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val runOptionsReference = "#/components/runOptions/kafka"
        val serviceReference = "#/components/services/kafkaService"
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val reference = RefOrValue.Reference(
            ref = serviceReference,
            extra = mapOf(
                "runOptions" to mapOf(
                    $$"$ref" to runOptionsReference,
                    "asyncapi" to mapOf(
                        "type" to "mock",
                        "inMemoryBroker" to mapOf("port" to 9092),
                    ),
                ),
            ),
        )

        MockServiceConfig(services = listOf(MockServiceConfig.Value(reference))).validate(
            context = contextFor(
                tempDir = tempDir,
                validator = validator,
                specification = specification,
                serviceReference = serviceReference,
                runOptionsReference = runOptionsReference,
            )
        )

        assertThat(validator.calls).containsExactly(
            Call(
                specification = specification.canonicalFile,
                configuration = mapOf("inMemoryBroker" to mapOf("port" to 9092)),
                location = "/dependencies/services/0/service/runOptions/asyncapi",
            )
        )
    }

    @Test
    fun `uses target location for a reference nested in a referenced service`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val runOptionsReference = "#/components/runOptions/kafka"
        val serviceReference = "#/components/services/kafkaService"
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }

        MockServiceConfig(services = listOf(MockServiceConfig.Value(RefOrValue.Reference(serviceReference)))).validate(
            context = contextFor(
                tempDir = tempDir,
                validator = validator,
                specification = specification,
                serviceReference = serviceReference,
                runOptionsReference = runOptionsReference,
                serviceRunOptions = mapOf($$"$ref" to runOptionsReference),
            )
        )

        assertThat(validator.calls).containsExactly(
            Call(
                specification = specification.canonicalFile,
                location = "/components/runOptions/kafka/asyncapi",
                configuration = mapOf("inMemoryBroker" to mapOf("port" to 9091)),
            )
        )
    }

    @Test
    fun `reports validator failures as configuration errors`(@TempDir tempDir: File) {
        val validator = RecordingValidator(failure = IllegalStateException("invalid broker configuration"))
        val definition = SpecificationDefinition.StringValue("events.yaml")
        val output = AsyncApiMockConfig()
            .withConfig(mapOf("broker" to "localhost:9092"))
            .validateForSpecFile(tempDir.resolve("events.yaml"), definition, context(tempDir, validator))

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

    private fun contextFor(
        tempDir: File,
        validator: RecordingValidator,
        specification: File,
        serviceReference: String,
        runOptionsReference: String,
        serviceRunOptions: Map<String, Any> = emptyMap(),
    ): ValidationContext {
        return ValidationContext(
            location = "/dependencies",
            protocolConfigValidators = listOf(validator),
            resolver = object : RefOrValueResolver {
                override fun resolveRef(reference: String): Any = when (reference) {
                    serviceReference -> mapOf(
                        "definitions" to listOf(
                            element = mapOf(
                                pair = "definition" to mapOf(
                                    "source" to mapOf("filesystem" to mapOf("directory" to tempDir.canonicalPath)),
                                    "specs" to listOf(specification.name),
                                ),
                            ),
                        ),
                        "runOptions" to serviceRunOptions,
                    )
                    runOptionsReference -> mapOf(pair = "asyncapi" to mapOf("type" to "mock", "inMemoryBroker" to mapOf("port" to 9091)))
                    else -> error("Unexpected reference: $reference")
                }
            },
        )
    }

    private data class Call(val specification: File, val configuration: Map<String, Any>, val location: String)
    private class RecordingValidator(private val failure: Throwable? = null) : ProtocolConfigValidator {
        val calls = mutableListOf<Call>()

        override fun supports(specType: SpecType, runOptionType: RunOptionType): Boolean {
            return specType == SpecType.ASYNCAPI && runOptionType == RunOptionType.MOCK
        }

        override fun validate(
            specification: File,
            context: ValidationContext,
            configuration: Map<String, Any>,
            definition: SpecificationDefinition
        ): List<ConfigValidationOutput> {
            calls += Call(specification, configuration, context.location)
            failure?.let { throw it }
            return emptyList()
        }
    }
}
