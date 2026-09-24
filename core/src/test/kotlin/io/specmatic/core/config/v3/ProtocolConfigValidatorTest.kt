package io.specmatic.core.config.v3

import io.specmatic.core.config.validation.ConfigValidationOutput
import io.specmatic.core.config.validation.ConfigValidationSeverity
import io.specmatic.core.config.v3.components.runOptions.AsyncApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.AsyncApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlMockConfig
import io.specmatic.core.config.v3.components.runOptions.IRunOptions
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.OpenApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.OpenApiRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.ProtobufMockConfig
import io.specmatic.core.config.v3.components.runOptions.RunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.RunOptionType
import io.specmatic.core.config.v3.components.runOptions.WsdlRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.WsdlMockConfig
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

class ProtocolConfigValidatorTest {
    @Test
    fun `invokes the SPI for AsyncAPI test config`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("spec").apply { writeText("spec") }
        val runOptions = AsyncApiTestConfig().withConfig(mapOf("servers" to emptyList<Any>()))

        runOptions.validateForSpecFile(
            specFile = specification,
            definition = SpecificationDefinition.StringValue(specification.path),
            validationContext = context(tempDir, validator),
        )

        assertThat(validator.applicableCalls.single()).isEqualTo(
            ApplicableCall(
                specification = specification,
                source = ApplicableSource.GLOBAL,
                runOptionType = RunOptionType.TEST,
                valueType = AsyncApiTestConfig::class,
                runOptionsType = AsyncApiTestConfig::class,
                location = "/dependencies/runOptions/asyncapi",
                runOptionsLocation = "/dependencies/runOptions/asyncapi",
            )
        )
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class MockProtocols {
        @ParameterizedTest(name = "{0}")
        @MethodSource("mockConfigurations")
        fun `invokes the SPI for every mock protocol`(protocol: MockProtocol, @TempDir tempDir: File) {
            val validator = RecordingValidator()
            val specification = tempDir.resolve("spec").apply { writeText("spec") }
            val definition = SpecificationDefinition.StringValue(specification.path)

            protocol.runOptions.validateForSpecFile(
                specFile = specification,
                definition = definition,
                validationContext = context(tempDir, validator),
            )

            assertThat(validator.applicableCalls).containsExactly(
                ApplicableCall(
                    specification = specification,
                    source = ApplicableSource.GLOBAL,
                    runOptionType = RunOptionType.MOCK,
                    valueType = protocol.runOptions::class,
                    runOptionsType = protocol.runOptions::class,
                    location = "/dependencies/runOptions/${protocol.configName}",
                    runOptionsLocation = "/dependencies/runOptions/${protocol.configName}",
                ),
            )
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("mockConfigurationsWithOverrides")
        fun `passes the matching override to the SPI for every mock protocol`(protocol: MockProtocol, @TempDir tempDir: File) {
            val validator = RecordingValidator()
            val specification = tempDir.resolve("spec").apply { writeText("spec") }
            val runOptionsWithOverride = requireNotNull(protocol.runOptionsWithOverride)
            val overrideType = requireNotNull(protocol.overrideType)
            val definition = SpecificationDefinition.ObjectValue(
                SpecificationDefinition.Specification(
                    id = "target",
                    path = specification.path,
                ),
            )

            runOptionsWithOverride.validateForSpecFile(
                specFile = specification,
                definition = definition,
                validationContext = context(tempDir, validator),
            )

            assertThat(validator.applicableCalls).containsExactly(
                ApplicableCall(
                    valueType = overrideType,
                    specification = specification,
                    source = ApplicableSource.OVERRIDE,
                    runOptionType = RunOptionType.MOCK,
                    runOptionsType = protocol.runOptions::class,
                    location = "/dependencies/runOptions/${protocol.configName}/specs/0",
                    runOptionsLocation = "/dependencies/runOptions/${protocol.configName}",
                ),
            )
        }

        fun mockConfigurations(): Stream<Arguments> = Stream.of(
            Arguments.of(
                MockProtocol(
                    name = "OpenAPI",
                    configName = "openapi",
                    runOptions = OpenApiMockConfig(port = 8080),
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "WSDL",
                    configName = "wsdl",
                    runOptions = WsdlMockConfig(port = 8081),
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "AsyncAPI",
                    configName = "asyncapi",
                    runOptions = AsyncApiMockConfig().withConfig(mapOf("broker" to "localhost:8082")),
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "GraphQL",
                    configName = "graphqlsdl",
                    runOptions = GraphQLSdlMockConfig().also { it.put("port", 8083) },
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "Protobuf",
                    configName = "protobuf",
                    runOptions = ProtobufMockConfig().also { it.put("port", 8084) },
                ),
            ),
        )

        fun mockConfigurationsWithOverrides(): Stream<Arguments> = Stream.of(
            Arguments.of(
                MockProtocol(
                    name = "OpenAPI",
                    configName = "openapi",
                    runOptions = OpenApiMockConfig(port = 8080),
                    runOptionsWithOverride = OpenApiMockConfig(
                        port = 8080,
                        specs = listOf(
                            OpenApiRunOptionsSpecifications(
                                OpenApiRunOptionsSpecifications.Value(id = "target", port = 8082),
                            ),
                        ),
                    ),
                    overrideType = OpenApiRunOptionsSpecifications::class,
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "WSDL",
                    configName = "wsdl",
                    runOptions = WsdlMockConfig(port = 8081),
                    runOptionsWithOverride = WsdlMockConfig(
                        port = 8081,
                        specs = listOf(
                            WsdlRunOptionsSpecifications(
                                WsdlRunOptionsSpecifications.Value(id = "target", port = 8083),
                            ),
                        ),
                    ),
                    overrideType = WsdlRunOptionsSpecifications::class,
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "AsyncAPI",
                    configName = "asyncapi",
                    runOptions = AsyncApiMockConfig(),
                    runOptionsWithOverride = AsyncApiMockConfig(
                        specs = listOf(
                            RunOptionsSpecifications(
                                RunOptionsSpecifications.Value(id = "target").withConfig(
                                    mapOf("broker" to "localhost:8084"),
                                ),
                            ),
                        ),
                    ),
                    overrideType = RunOptionsSpecifications::class,
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "GraphQL",
                    configName = "graphqlsdl",
                    runOptions = GraphQLSdlMockConfig(),
                    runOptionsWithOverride = GraphQLSdlMockConfig(
                        specs = listOf(
                            RunOptionsSpecifications(
                                RunOptionsSpecifications.Value(id = "target").withConfig(
                                    mapOf("port" to 8085),
                                ),
                            ),
                        ),
                    ),
                    overrideType = RunOptionsSpecifications::class,
                ),
            ),
            Arguments.of(
                MockProtocol(
                    name = "Protobuf",
                    configName = "protobuf",
                    runOptions = ProtobufMockConfig(),
                    runOptionsWithOverride = ProtobufMockConfig(
                        specs = listOf(
                            RunOptionsSpecifications(
                                RunOptionsSpecifications.Value(id = "target").withConfig(
                                    mapOf("port" to 8086),
                                ),
                            ),
                        ),
                    ),
                    overrideType = RunOptionsSpecifications::class,
                ),
            ),
        )
    }

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
    fun `passes an overlay-only async mock specification override to the SPI`(@TempDir tempDir: File) {
        val validator = RecordingValidator()
        val specification = tempDir.resolve("events.yaml").apply { writeText("asyncapi: 3.0.0") }
        val definition = SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "kafka", path = specification.path))

        val baseConfiguration = mapOf("inMemoryBroker" to mapOf("port" to 9091))
        val runOptions = AsyncApiMockConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "kafka", overlayFilePath = "overlay.yaml"),
                ),
            ),
        ).withConfig(baseConfiguration)

        runOptions.validateForSpecFile(specification, definition, context(tempDir, validator))
        assertThat(validator.calls).containsExactly(
            Call(
                specification = specification,
                configuration = emptyMap(),
                location = "/dependencies/runOptions/asyncapi/specs/0",
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
    fun `typed validator receives typed global and override configurations`(@TempDir tempDir: File) {
        val validator = TypedRecordingValidator()
        val validationContext = context(tempDir, validator)
        val global = AsyncApiMockConfig().withConfig(mapOf("broker" to "global"))
        val override = RunOptionsSpecifications(RunOptionsSpecifications.Value(id = "target").withConfig(mapOf("broker" to "override")))

        val specification = tempDir.resolve("events.yaml")
        global.validateForSpecFile(
            specFile = specification,
            validationContext = validationContext,
            definition = SpecificationDefinition.StringValue(specification.path),
        )

        global.copy(specs = listOf(override)).validateForSpecFile(
            specFile = specification,
            validationContext = validationContext,
            definition = SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "target", path = specification.path)),
        )

        assertThat(validator.calls).containsExactly(
            TypedCall(
                source = ApplicableSource.GLOBAL,
                configuration = mapOf("broker" to "global"),
                configurationType = AsyncApiMockConfig::class,
                contextLocation = "/dependencies/runOptions/asyncapi",
                location = "/dependencies/runOptions/asyncapi",
                runOptionsConfiguration = mapOf("broker" to "global"),
                runOptionsLocation = "/dependencies/runOptions/asyncapi",
            ),
            TypedCall(
                source = ApplicableSource.OVERRIDE,
                configuration = mapOf("broker" to "override"),
                configurationType = RunOptionsSpecifications::class,
                contextLocation = "/dependencies/runOptions/asyncapi/specs/0",
                runOptionsConfiguration = mapOf("broker" to "global"),
                location = "/dependencies/runOptions/asyncapi/specs/0",
                runOptionsLocation = "/dependencies/runOptions/asyncapi",
            ),
        )
    }

    @Test
    fun `typed validator ignores an unrelated protocol configuration`(@TempDir tempDir: File) {
        val validator = TypedRecordingValidator()
        val specification = tempDir.resolve("openapi.yaml")
        OpenApiMockConfig().validateForSpecFile(
            specFile = specification,
            definition = SpecificationDefinition.StringValue(specification.path),
            validationContext = context(tempDir, validator),
        )

        assertThat(validator.calls).isEmpty()
    }

    @Test
    fun `typed validator ignores an incompatible specification override`(@TempDir tempDir: File) {
        val validator = TypedRecordingValidator()
        val specification = tempDir.resolve("events.yaml")
        val context = context(tempDir, validator)

        val output = validator.validate(
            specification = specification,
            definition = SpecificationDefinition.StringValue(specification.path),
            configuration = ApplicableProtocolConfig.Override(
                runOptionType = RunOptionType.MOCK,
                runOptions = ValueWithContext(value = AsyncApiMockConfig(), context = context),
                specOverride = ValueWithContext(
                    context = context.child("specs").child(0),
                    value = OpenApiRunOptionsSpecifications(OpenApiRunOptionsSpecifications.Value(id = "events", port = 8080)),
                ),
            ),
        )

        assertThat(output).isEmpty()
        assertThat(validator.calls).isEmpty()
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
        val specification = tempDir.resolve("events.yaml")
        val output = AsyncApiMockConfig()
            .withConfig(mapOf("broker" to "localhost:9092"))
            .validateForSpecFile(specification, definition, context(tempDir, validator))

        assertThat(output).containsExactly(
            ConfigValidationOutput(
                valid = false,
                keywordLocation = "",
                severity = ConfigValidationSeverity.ERROR,
                instanceLocation = "/dependencies/runOptions/asyncapi",
                error = "Protocol validation failed for '${specification.path}': invalid broker configuration",
            ),
        )
    }

    @Test
    fun `reports override validator failures at the override location`(@TempDir tempDir: File) {
        val validator = RecordingValidator(failure = IllegalStateException("invalid broker override"))
        val specification = tempDir.resolve("events.yaml")
        val definition = SpecificationDefinition.ObjectValue(SpecificationDefinition.Specification(id = "target", path = specification.path))

        val runOptions = AsyncApiMockConfig(
            specs = listOf(
                element = RunOptionsSpecifications(
                    spec = RunOptionsSpecifications.Value(id = "target").withConfig(mapOf("broker" to "override")),
                ),
            ),
        ).withConfig(mapOf("broker" to "global"))

        val output = runOptions.validateForSpecFile(
            specFile = specification,
            definition = definition,
            validationContext = context(tempDir, validator),
        )

        assertThat(output).containsExactly(
            ConfigValidationOutput(
                valid = false,
                keywordLocation = "",
                severity = ConfigValidationSeverity.ERROR,
                instanceLocation = "/dependencies/runOptions/asyncapi/specs/0",
                error = "Protocol validation failed for '${specification.path}': invalid broker override",
            ),
        )
    }

    private fun context(tempDir: File, validator: ProtocolConfigValidator): ValidationContext {
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
    private enum class ApplicableSource { GLOBAL, OVERRIDE }
    private data class ApplicableCall(
        val location: String,
        val specification: File,
        val source: ApplicableSource,
        val runOptionType: RunOptionType,
        val runOptionsLocation: String,
        val valueType: kotlin.reflect.KClass<*>,
        val runOptionsType: kotlin.reflect.KClass<*>,
    )

    data class MockProtocol(
        val name: String,
        val configName: String,
        val runOptions: IRunOptions,
        val runOptionsWithOverride: IRunOptions? = null,
        val overrideType: kotlin.reflect.KClass<*>? = null,
    ) {
        override fun toString(): String = name
    }

    private data class TypedCall(
        val location: String,
        val source: ApplicableSource,
        val configuration: Map<String, Any>,
        val contextLocation: String,
        val runOptionsLocation: String = "",
        val configurationType: kotlin.reflect.KClass<*>,
        val runOptionsConfiguration: Map<String, Any> = emptyMap(),
    )

    private class RecordingValidator(private val failure: Throwable? = null) : ProtocolConfigValidator {
        val calls = mutableListOf<Call>()
        val applicableCalls = mutableListOf<ApplicableCall>()

        override fun validate(specification: File, definition: SpecificationDefinition, configuration: ApplicableProtocolConfig<*, *>): List<ConfigValidationOutput> {
            val call = when (configuration) {
                is ApplicableProtocolConfig.Global -> {
                    applicableCalls += ApplicableCall(
                        specification = specification,
                        source = ApplicableSource.GLOBAL,
                        runOptionType = configuration.runOptionType,
                        valueType = configuration.runOptions.value::class,
                        location = configuration.runOptions.context.location,
                        runOptionsType = configuration.runOptions.value::class,
                        runOptionsLocation = configuration.runOptions.context.location,
                    )
                    Call(specification, configuration.runOptions.value.config, configuration.runOptions.context.location)
                }

                is ApplicableProtocolConfig.Override -> {
                    applicableCalls += ApplicableCall(
                        specification = specification,
                        source = ApplicableSource.OVERRIDE,
                        runOptionType = configuration.runOptionType,
                        valueType = configuration.specOverride.value::class,
                        location = configuration.specOverride.context.location,
                        runOptionsType = configuration.runOptions.value::class,
                        runOptionsLocation = configuration.runOptions.context.location,
                    )
                    Call(specification, configuration.specOverride.value.getConfig(), configuration.specOverride.context.location)
                }
            }

            calls += call
            failure?.let { throw it }
            return emptyList()
        }
    }

    private class TypedRecordingValidator : TypedProtocolConfigValidator<AsyncApiMockConfig, RunOptionsSpecifications>(
        globalType = AsyncApiMockConfig::class,
        overrideType = RunOptionsSpecifications::class,
    ) {
        val calls = mutableListOf<TypedCall>()

        override fun validateTyped(
            specification: File,
            definition: SpecificationDefinition,
            configuration: ApplicableProtocolConfig<AsyncApiMockConfig, RunOptionsSpecifications>,
        ): List<ConfigValidationOutput> {
            calls += when (configuration) {
                is ApplicableProtocolConfig.Global -> TypedCall(
                    source = ApplicableSource.GLOBAL,
                    contextLocation = configuration.context.location,
                    location = configuration.runOptions.context.location,
                    configuration = configuration.runOptions.value.config,
                    configurationType = configuration.runOptions.value::class,
                    runOptionsLocation = configuration.runOptions.context.location,
                    runOptionsConfiguration = configuration.runOptions.value.config,
                )

                is ApplicableProtocolConfig.Override -> TypedCall(
                    source = ApplicableSource.OVERRIDE,
                    contextLocation = configuration.context.location,
                    location = configuration.specOverride.context.location,
                    configurationType = configuration.specOverride.value::class,
                    configuration = configuration.specOverride.value.getConfig(),
                    runOptionsLocation = configuration.runOptions.context.location,
                    runOptionsConfiguration = configuration.runOptions.value.config,
                )
            }

            return emptyList()
        }
    }

}
