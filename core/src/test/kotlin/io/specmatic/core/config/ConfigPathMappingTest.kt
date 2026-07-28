package io.specmatic.core.config

import io.specmatic.core.TestConfiguration
import io.specmatic.core.StubConfiguration
import io.specmatic.core.config.v2.SpecExecutionConfig.ConfigValue
import io.specmatic.core.config.v2.ContractConfig
import io.specmatic.core.config.v2.SpecExecutionConfig
import io.specmatic.core.config.v2.SpecmaticConfigV2
import io.specmatic.core.config.v3.components.Adapter
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.Components
import io.specmatic.core.config.v3.ContextDependentSettings
import io.specmatic.core.config.v3.Proxy
import io.specmatic.core.config.v3.ProxyConfigV3
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.Specmatic
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.components.Dictionary
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.Examples
import io.specmatic.core.config.v3.components.runOptions.ContextDependentRunOptions
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlMockConfig
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlTestConfig
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.runOptions.WsdlMockConfig
import io.specmatic.core.config.v3.components.runOptions.WsdlTestConfig
import io.specmatic.core.config.v3.components.settings.MockSettings
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.specmatic.License
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class ConfigPathMappingTest {
    private val configDirectory = File("/config")
    private val mapper = MappingConfigPathMapper { location, path, base ->
        "${location.joinToString(".")}|${base.path}|$path"
    }

    @Nested
    inner class V2 {
        @Test
        fun `maps root fields and filesystem execution base`() {
            val config = SpecmaticConfigV2(
                version = SpecmaticConfigVersion.VERSION_2,
                examples = listOf("examples"),
                test = TestConfiguration(overlayFilePath = "overlay.yaml"),
                contracts = listOf(
                    ContractConfig(
                        contractSource = ContractConfig.FileSystemContractSource("contracts"),
                        provides = listOf(SpecExecutionConfig.StringValue("api.yaml"))
                    )
                )
            )

            val mapped = config.mapPaths(mapper, configDirectory)
            assertThat(mapped.examples).isEqualTo(listOf("examples.0|${configDirectory.path}|examples"))
            assertThat(mapped.test?.overlayFilePath).isEqualTo("test.overlayFilePath|${configDirectory.path}|overlay.yaml")
            assertThat(mapped.contracts.single().getFilesystemSource()?.directory)
                .isEqualTo("contracts.0.filesystem.directory|${configDirectory.path}|contracts")
            assertThat((mapped.contracts.single().provides!!.single() as SpecExecutionConfig.StringValue).value)
                .isEqualTo("contracts.0.provides.0|${resolvedBase("contracts.0.filesystem.directory", "contracts")}|api.yaml")
        }

        @Test
        fun `maps https dictionary and mcp paths`() {
            val config = SpecmaticConfigV2(
                version = SpecmaticConfigVersion.VERSION_2,
                test = TestConfiguration(
                    https = HttpsConfiguration(
                        keyStore = KeyStoreConfiguration.FileBasedConfig(file = "test.p12")
                    )
                ),
                stub = StubConfiguration(
                    dictionary = "stub.json",
                    https = HttpsConfiguration(
                        keyStore = KeyStoreConfiguration.DirectoryBasedConfig(directory = "certs")
                    )
                ),
                mcp = McpConfiguration(McpTestConfiguration(baseUrl = "http://mcp", dictionaryFile = "mcp.json"))
            )

            val mapped = config.mapPaths(mapper, configDirectory)
            assertThat((mapped.test?.https?.keyStore as KeyStoreConfiguration.FileBasedConfig).file)
                .isEqualTo("test.https.keyStore.file|${configDirectory.path}|test.p12")

            assertThat(mapped.stub?.getDictionary()).isEqualTo("stub.dictionary|${configDirectory.path}|stub.json")
            assertThat((mapped.stub?.getHttps()?.keyStore as KeyStoreConfiguration.DirectoryBasedConfig).directory)
                .isEqualTo("stub.https.keyStore.directory|${configDirectory.path}|certs")

            assertThat(mapped.mcp?.test?.dictionaryFile).isEqualTo("mcp.test.dictionaryFile|${configDirectory.path}|mcp.json")
        }

        @Test
        fun `maps only string values in dynamic path lists`() {
            val execution = ConfigValue(
                specs = listOf("api.yaml"),
                specType = "openapi",
                config = mapOf("examples" to listOf("examples", 42), "unknown" to listOf("untouched"))
            )

            val mapped = execution.mapPaths(mapper.child("execution"), configDirectory, configDirectory)
            assertThat(mapped).isEqualTo(
                ConfigValue(
                    specs = listOf("execution.specs.0|${configDirectory.path}|api.yaml"),
                    specType = "openapi",
                    config = mapOf(
                        "examples" to listOf("execution.config.examples.0|${configDirectory.path}|examples", 42),
                        "unknown" to listOf("untouched")
                    )
                )
            )
        }
    }

    @Nested
    inner class V3 {
        @Test
        fun `maps components examples dictionary license and proxy paths`() {
            val config = SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                components = Components(
                    dictionaries = mapOf("main" to Dictionary("dictionary.json")),
                    examples = Examples(
                        testExamples = listOf(RefOrValue.Value(ExampleDirectories(listOf("examples"))))
                    )
                ),
                proxies = listOf(
                    Proxy(
                        ProxyConfigV3(
                            target = "http://target",
                            mock = listOf("mock.yaml"),
                            recordingsDirectory = "recordings"
                        )
                    )
                ),
                specmatic = Specmatic(license = License("license.txt"))
            )

            val mapped = config.mapPaths(mapper, configDirectory)
            assertThat(mapped.components?.dictionaries?.get("main")?.path)
                .isEqualTo("components.dictionaries.main.path|${configDirectory.path}|dictionary.json")
            assertThat(mapped.components?.examples?.testExamples?.single()?.getOrNull()?.directories)
                .isEqualTo(listOf("components.examples.testExamples.0.directories.0|${configDirectory.path}|examples"))
            assertThat(mapped.proxies?.single()?.proxy?.mock)
                .isEqualTo(listOf("proxies.0.proxy.mock.0|${configDirectory.path}|mock.yaml"))
            assertThat(mapped.proxies!!.single().proxy.recordingsDirectory)
                .isEqualTo("proxies.0.proxy.recordingsDirectory|${configDirectory.path}|recordings")
            assertThat(mapped.specmatic?.license?.path)
                .isEqualTo("specmatic.license.path|${configDirectory.path}|license.txt")
        }

        @Test
        fun `recalculates definition spec base after source mapping`() {
            val definition = Definition(
                Definition.Value(
                    source = RefOrValue.Value(SourceV3.create(filesystem = SourceV3.FileSystem("specs"))),
                    specs = listOf(SpecificationDefinition.StringValue("api.yaml"))
                )
            )

            val mapped = definition.mapPaths(mapper.child("definition"), configDirectory)
            assertThat(mapped.definition.source.getOrNull()?.getFileSystem()?.directory)
                .isEqualTo("definition.source.fileSystem.directory|${configDirectory.path}|specs")
            assertThat(mapped.definition.specs.single().getSpecificationPath())
                .isEqualTo("definition.specs.0|${resolvedBase("definition.source.fileSystem.directory", "specs")}|api.yaml")
        }

        @Test
        fun `preserves references while mapping inline component values`() {
            val config = SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                components = Components(
                    sources = mapOf("source" to SourceV3.create(filesystem = SourceV3.FileSystem("specs"))),
                    adapters = mapOf("adapter" to Adapter(mapOf("hook" to "hook.kts"))),
                    certificates = mapOf("cert" to HttpsConfiguration()),
                    examples = Examples(
                        testExamples = listOf(RefOrValue.Reference("#/components/examples/reusable"))
                    )
                ),
                systemUnderTest = TestServiceConfig(
                    RefOrValue.Value(
                        CommonServiceConfig(
                            definitions = listOf(
                                Definition(
                                    Definition.Value(
                                        source = RefOrValue.Reference("#/components/sources/source"),
                                        specs = listOf(SpecificationDefinition.StringValue("api.yaml"))
                                    )
                                )
                            )
                        )
                    )
                )
            )

            val mapped = config.mapPaths(mapper, configDirectory)
            assertThat(mapped.components?.examples?.testExamples)
                .isEqualTo(listOf(RefOrValue.Reference("#/components/examples/reusable")))
            assertThat(mapped.systemUnderTest?.service?.getOrNull()?.definitions?.single()?.definition?.source)
                .isEqualTo(RefOrValue.Reference("#/components/sources/source"))
            assertThat(mapped.components?.sources?.get("source")?.getFileSystem()?.directory)
                .isEqualTo("components.sources.source.fileSystem.directory|${configDirectory.path}|specs")
        }

        @Test
        fun `maps referenced services and preserves context dependent values`() {
            val service = CommonServiceConfig(
                definitions = listOf(
                    element = Definition(
                        Definition.Value(
                            source = RefOrValue.Value(SourceV3.create(filesystem = SourceV3.FileSystem("specs"))),
                            specs = listOf(SpecificationDefinition.StringValue("api.yaml"))
                        )
                    )
                ),
                settings = RefOrValue.Value(ContextDependentSettings(mapOf("custom" to "setting"))),
                runOptions = RefOrValue.Value(ContextDependentRunOptions(mapOf("protobuf" to mapOf("importPaths" to listOf("./proto-imports"))))),
            )

            val config = SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                components = Components(services = mapOf("service" to service)),
                systemUnderTest = TestServiceConfig(RefOrValue.Reference("#/components/services/service"))
            )

            val mapped = config.mapPaths(mapper, configDirectory)
            val mappedService = mapped.components!!.services!!["service"]!!

            assertThat(mapped.systemUnderTest?.service)
                .isEqualTo(RefOrValue.Reference("#/components/services/service"))
            assertThat(mappedService.definitions.single().definition.source.getOrNull()?.getFileSystem()?.directory)
                .isEqualTo("components.services.service.definitions.0.source.fileSystem.directory|${configDirectory.path}|specs")
            assertThat(mappedService.definitions.single().definition.specs.single().getSpecificationPath())
                .isEqualTo("components.services.service.definitions.0.specs.0|${resolvedBase("components.services.service.definitions.0.source.fileSystem.directory", "specs")}|api.yaml")
            assertThat(mappedService.runOptions)
                .isEqualTo(
                    RefOrValue.Value(
                        ContextDependentRunOptions(
                            mapOf("protobuf" to mapOf("importPaths" to listOf("./proto-imports")))
                        )
                    )
                )
            assertThat(mappedService.settings)
                .isEqualTo(RefOrValue.Value(ContextDependentSettings(mapOf("custom" to "setting"))))
        }

        @Test
        fun `maps ref overlays while preserving the reference`() {
            val overlay = mapOf(
                pair = "runOptions" to mapOf("protobuf" to mapOf("importPaths" to listOf("./proto-imports")))
            )

            val config = SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                components = Components(services = mapOf("service" to CommonServiceConfig(definitions = emptyList()))),
                systemUnderTest = TestServiceConfig(RefOrValue.Reference(ref = "#/components/services/service", extra = overlay))
            )

            val mapped = config.mapPaths(mapper, configDirectory)
            val mappedExtra = (mapped.systemUnderTest?.service as RefOrValue.Reference).extra
            val mappedRunOptions = mappedExtra["runOptions"] as Map<*, *>
            val mappedProtobuf = mappedRunOptions["protobuf"] as Map<*, *>
            assertThat(mappedProtobuf["importPaths"])
                .isEqualTo(listOf("systemUnderTest.service.runOptions.protobuf.config.importPaths.0|${configDirectory.path}|./proto-imports"))
        }

        @Test
        fun `preserves dynamic WSDL and GraphQL run option config while mapping`() {
            val configs = listOf(
                WsdlTestConfig().also { it.put("custom", "test-wsdl") },
                WsdlMockConfig().also { it.put("custom", "mock-wsdl") },
                GraphQLSdlTestConfig().also { it.put("custom", "test-graphql") },
                GraphQLSdlMockConfig().also { it.put("custom", "mock-graphql") },
            )

            assertThat(configs.map { it.mapPaths(mapper, configDirectory).config })
                .containsExactly(
                    mapOf("custom" to "test-wsdl"),
                    mapOf("custom" to "mock-wsdl"),
                    mapOf("custom" to "test-graphql"),
                    mapOf("custom" to "mock-graphql"),
                )
        }

        @Test
        fun `preserves dynamic specification definition config while mapping`() {
            val specification = SpecificationDefinition.Specification(
                id = "api",
                path = "api.yaml"
            ).also {
                it.put("custom", "value")
            }

            val definition = Definition(
                Definition.Value(
                    source = RefOrValue.Value(SourceV3.create(filesystem = SourceV3.FileSystem("specs"))),
                    specs = listOf(SpecificationDefinition.ObjectValue(specification))
                )
            )

            val mappedSpecification = (definition.mapPaths(mapper, configDirectory).definition.specs.single() as SpecificationDefinition.ObjectValue).spec
            assertThat(mappedSpecification.config).containsEntry("custom", "value")
        }
    }

    @Nested
    inner class ServiceDefinitionMapping {
        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `maps test service definitions exactly once`(referenced: Boolean) {
            val config = testConfig(referenced)

            val mappedLocations = mutableListOf<String>()
            config.mapPaths(RecordingConfigPathMapper(mappedLocations), configDirectory)
            val serviceLocation = if (referenced) {
                "components.services.service"
            } else {
                "systemUnderTest.service"
            }

            assertThat(mappedLocations).containsExactly(
                "$serviceLocation.definitions.0.source.fileSystem.directory",
                "$serviceLocation.definitions.0.specs.0",
            )
        }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `maps mock service definitions exactly once`(referenced: Boolean) {
            val config = mockConfig(referenced)

            val mappedLocations = mutableListOf<String>()
            config.mapPaths(RecordingConfigPathMapper(mappedLocations), configDirectory)
            val serviceLocation = if (referenced) {
                "components.services.service"
            } else {
                "dependencies.services.0.service"
            }

            assertThat(mappedLocations).containsExactly(
                "$serviceLocation.definitions.0.source.fileSystem.directory",
                "$serviceLocation.definitions.0.specs.0",
            )
        }

        private fun testConfig(referenced: Boolean): SpecmaticConfigV3 {
            val componentService = componentService()
            val inlineService = testService()
            return SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                components = if (referenced) Components(services = mapOf("service" to componentService)) else null,
                systemUnderTest = TestServiceConfig(
                    if (referenced) RefOrValue.Reference("#/components/services/service") else RefOrValue.Value(inlineService)
                ),
            )
        }

        private fun mockConfig(referenced: Boolean): SpecmaticConfigV3 {
            val componentService = componentService()
            val inlineService = mockService()
            return SpecmaticConfigV3(
                version = SpecmaticConfigVersion.VERSION_3,
                components = if (referenced) Components(services = mapOf("service" to componentService)) else null,
                dependencies = MockServiceConfig(
                    services = listOf(
                        MockServiceConfig.Value(
                            if (referenced) RefOrValue.Reference("#/components/services/service") else RefOrValue.Value(inlineService)
                        )
                    )
                ),
            )
        }

        private fun testService(): CommonServiceConfig<TestRunOptions, TestSettings> = CommonServiceConfig(definitions = definitions())
        private fun mockService(): CommonServiceConfig<MockRunOptions, MockSettings> = CommonServiceConfig(definitions = definitions())
        private fun componentService(): CommonServiceConfig<ContextDependentRunOptions, ContextDependentSettings> = CommonServiceConfig(definitions = definitions(),)
        private fun definitions() = listOf(
            element = Definition(
                Definition.Value(
                    source = RefOrValue.Value(SourceV3.create(filesystem = SourceV3.FileSystem("specs"))),
                    specs = listOf(SpecificationDefinition.StringValue("api.yaml")),
                )
            )
        )
    }

    @Test
    fun `no-op mapper preserves complete aggregate`() {
        val config = SpecmaticConfigV3(version = SpecmaticConfigVersion.VERSION_3, components = Components())
        assertThat(config.mapPaths(NoOpConfigPathMapper, configDirectory)).isEqualTo(config)
    }

    private fun resolvedBase(location: String, path: String): String {
        val mappedDirectory = "$location|${configDirectory.path}|$path"
        return configDirectory.resolve(mappedDirectory).path
    }
}

private class MappingConfigPathMapper(
    private val location: List<String> = emptyList(),
    private val mapping: (location: List<String>, path: String, baseDirectory: File) -> String
) : ConfigPathMapper {
    override fun child(segment: String) = MappingConfigPathMapper(location + segment, mapping)
    override fun child(index: Int) = MappingConfigPathMapper(location + index.toString(), mapping)
    override fun map(path: String, baseDirectory: File) = mapping(location, path, baseDirectory)
}


private object NoOpConfigPathMapper : ConfigPathMapper {
    override fun child(segment: String) = this
    override fun child(index: Int) = this
    override fun map(path: String, baseDirectory: File) = path
}

private class RecordingConfigPathMapper(
    private val mappedLocations: MutableList<String>,
    private val location: List<String> = emptyList(),
) : ConfigPathMapper {
    override fun child(segment: String) = RecordingConfigPathMapper(mappedLocations, location + segment)
    override fun child(index: Int) = RecordingConfigPathMapper(mappedLocations, location + index.toString())
    override fun map(path: String, baseDirectory: File): String {
        mappedLocations += location.joinToString(".")
        return path
    }
}
