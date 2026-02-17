package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.components.runOptions.AsyncApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.GraphQLSdlTestConfig
import io.specmatic.core.config.v3.components.runOptions.OpenApiRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.OpenApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.ProtobufTestConfig
import io.specmatic.core.config.v3.components.runOptions.RunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
import io.specmatic.core.config.v3.components.runOptions.WsdlRunOptionsSpecifications
import io.specmatic.core.config.v3.components.runOptions.WsdlTestConfig
import io.specmatic.core.config.v3.components.settings.TestSettings
import io.specmatic.core.config.v3.components.sources.SourceV3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestServiceConfigTest {
    private val resolver = object : RefOrValueResolver {
        override fun resolveRef(reference: String): Map<*, *> {
            error("Unexpected ref resolution in test: $reference")
        }
    }

    @Test
    fun `getSpecificationSources should use asyncapi inMemoryBroker for async runOptions`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("contract.yaml").apply { writeText("asyncapi: 3.0.0") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val asyncApiTestConfig = AsyncApiTestConfig().apply {
            put("inMemoryBroker", mapOf("host" to "localhost", "port" to 8082))
        }

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(SpecificationDefinition.StringValue(specFile.name))
                    )
                )
            ),
            runOptions = RefOrValue.Value(TestRunOptions(asyncapi = asyncApiTestConfig))
        )
        val testServiceConfig = TestServiceConfig(service = RefOrValue.Value(serviceConfig))
        val sourceEntries = testServiceConfig.getSpecificationSources(resolver, testSettings = null).values.flatten()

        assertThat(sourceEntries).hasSize(1)
        assertThat(sourceEntries.single().baseUrl).isEqualTo("localhost:8082")
    }

    @Test
    fun `getSpecificationSources should retain specs from multiple definitions with same source`(@TempDir tempDir: File) {
        val firstSpec = tempDir.resolve("first.txt").apply { writeText("not an openapi spec") }
        val secondSpec = tempDir.resolve("second.txt").apply { writeText("not an openapi spec") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(SpecificationDefinition.StringValue(firstSpec.name))
                    )
                ),
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(SpecificationDefinition.StringValue(secondSpec.name))
                    )
                )
            )
        )

        val testServiceConfig = TestServiceConfig(service = RefOrValue.Value(serviceConfig))

        val sourceEntriesBySource = testServiceConfig.getSpecificationSources(resolver, testSettings = null)

        assertThat(sourceEntriesBySource).hasSize(1)
        assertThat(sourceEntriesBySource.values.single()).hasSize(2)
    }

    @Test
    fun `getSpecificationSources should use openapi per-spec baseUrl when spec id matches`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("orders.yaml").apply { writeText("openapi: 3.0.0") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(
                                    id = "orders-spec",
                                    path = specFile.name
                                )
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(
                TestRunOptions(
                    openapi = OpenApiTestConfig(
                        baseUrl = "http://default-base-url:9000",
                        specs = listOf(
                            OpenApiRunOptionsSpecifications(
                                spec = OpenApiRunOptionsSpecifications.Value(
                                    id = "orders-spec",
                                    baseUrl = "http://spec-level-base-url:9001"
                                )
                            )
                        )
                    )
                )
            )
        )

        val testServiceConfig = TestServiceConfig(service = RefOrValue.Value(serviceConfig))
        val sourceEntries = testServiceConfig.getSpecificationSources(resolver, testSettings = null).values.flatten()

        assertThat(sourceEntries).hasSize(1)
        assertThat(sourceEntries.single().baseUrl).isEqualTo("http://spec-level-base-url:9001")
    }

    @Test
    fun `getSpecificationSources should fallback to openapi runOption baseUrl when spec id has no match`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("orders.yaml").apply { writeText("openapi: 3.0.0") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(
                                    id = "orders-spec",
                                    path = specFile.name
                                )
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(
                TestRunOptions(
                    openapi = OpenApiTestConfig(
                        baseUrl = "http://default-base-url:9000",
                        specs = listOf(
                            OpenApiRunOptionsSpecifications(
                                spec = OpenApiRunOptionsSpecifications.Value(
                                    id = "different-spec-id",
                                    baseUrl = "http://spec-level-base-url:9001"
                                )
                            )
                        )
                    )
                )
            )
        )

        val testServiceConfig = TestServiceConfig(service = RefOrValue.Value(serviceConfig))
        val sourceEntries = testServiceConfig.getSpecificationSources(resolver, testSettings = null).values.flatten()

        assertThat(sourceEntries).hasSize(1)
        assertThat(sourceEntries.single().baseUrl).isEqualTo("http://default-base-url:9000")
    }

    @Test
    fun `getSpecificationSources should use wsdl per-spec baseUrl when spec id matches`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("service.wsdl").apply { writeText("<definitions/>") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(id = "wsdl-spec", path = specFile.name)
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(
                TestRunOptions(
                    wsdl = WsdlTestConfig(
                        baseUrl = "http://wsdl-default:9100",
                        specs = listOf(
                            WsdlRunOptionsSpecifications(
                                WsdlRunOptionsSpecifications.Value(
                                    id = "wsdl-spec",
                                    baseUrl = "http://wsdl-spec:9101"
                                )
                            )
                        )
                    )
                )
            )
        )

        val entries = TestServiceConfig(service = RefOrValue.Value(serviceConfig)).getSpecificationSources(resolver, null).values.flatten()
        assertThat(entries.single().baseUrl).isEqualTo("http://wsdl-spec:9101")
    }

    @Test
    fun `getSpecificationSources should fallback to wsdl runOption baseUrl when spec id has no match`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("service.wsdl").apply { writeText("<definitions/>") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(id = "wsdl-spec", path = specFile.name)
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(
                TestRunOptions(
                    wsdl = WsdlTestConfig(
                        baseUrl = "http://wsdl-default:9100",
                        specs = listOf(
                            WsdlRunOptionsSpecifications(
                                WsdlRunOptionsSpecifications.Value(
                                    id = "different-spec-id",
                                    baseUrl = "http://wsdl-spec:9101"
                                )
                            )
                        )
                    )
                )
            )
        )

        val entries = TestServiceConfig(service = RefOrValue.Value(serviceConfig)).getSpecificationSources(resolver, null).values.flatten()
        assertThat(entries.single().baseUrl).isEqualTo("http://wsdl-default:9100")
    }

    @Test
    fun `getSpecificationSources should use protobuf per-spec baseUrl when spec id matches`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("service.proto").apply { writeText("syntax = \"proto3\";") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val protobufConfig = ProtobufTestConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "proto-spec", host = "proto-spec", port = 9201)
                )
            )
        ).apply {
            put("host", "proto-default")
            put("port", 9200)
        }

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(id = "proto-spec", path = specFile.name)
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(TestRunOptions(protobuf = protobufConfig))
        )

        val entries = TestServiceConfig(service = RefOrValue.Value(serviceConfig)).getSpecificationSources(resolver, null).values.flatten()
        assertThat(entries.single().baseUrl).isEqualTo("proto-spec:9201")
    }

    @Test
    fun `getSpecificationSources should fallback to protobuf runOption baseUrl when spec id has no match`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("service.proto").apply { writeText("syntax = \"proto3\";") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val protobufConfig = ProtobufTestConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "different-spec-id", host = "proto-spec", port = 9201)
                )
            )
        ).apply {
            put("host", "proto-default")
            put("port", 9200)
        }

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(id = "proto-spec", path = specFile.name)
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(TestRunOptions(protobuf = protobufConfig))
        )

        val entries = TestServiceConfig(service = RefOrValue.Value(serviceConfig)).getSpecificationSources(resolver, null).values.flatten()
        assertThat(entries.single().baseUrl).isEqualTo("proto-default:9200")
    }

    @Test
    fun `getSpecificationSources should use graphql per-spec baseUrl when spec id matches`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("service.graphql").apply { writeText("type Query { ping: String }") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val graphConfig = GraphQLSdlTestConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "graphql-spec", host = "graphql-spec", port = 9301)
                )
            )
        ).apply {
            put("host", "graphql-default")
            put("port", 9300)
        }

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(id = "graphql-spec", path = specFile.name)
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(TestRunOptions(graphqlsdl = graphConfig))
        )

        val entries = TestServiceConfig(service = RefOrValue.Value(serviceConfig)).getSpecificationSources(resolver, null).values.flatten()
        assertThat(entries.single().baseUrl).isEqualTo("graphql-spec:9301")
    }

    @Test
    fun `getSpecificationSources should fallback to graphql runOption baseUrl when spec id has no match`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("service.graphql").apply { writeText("type Query { ping: String }") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val graphConfig = GraphQLSdlTestConfig(
            specs = listOf(
                RunOptionsSpecifications(
                    RunOptionsSpecifications.Value(id = "different-spec-id", host = "graphql-spec", port = 9301)
                )
            )
        ).apply {
            put("host", "graphql-default")
            put("port", 9300)
        }

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.ObjectValue(
                                SpecificationDefinition.Specification(id = "graphql-spec", path = specFile.name)
                            )
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(TestRunOptions(graphqlsdl = graphConfig))
        )

        val entries = TestServiceConfig(service = RefOrValue.Value(serviceConfig)).getSpecificationSources(resolver, null).values.flatten()
        assertThat(entries.single().baseUrl).isEqualTo("graphql-default:9300")
    }

    @Test
    fun `getSpecificationSources should resolve openapi and asyncapi yaml specs in same service`(@TempDir tempDir: File) {
        val openApiSpecFile = tempDir.resolve("openapi-contract.yaml").apply { writeText("openapi: 3.0.0") }
        val asyncApiSpecFile = tempDir.resolve("asyncapi-contract.yaml").apply { writeText("asyncapi: 2.6.0") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))
        val asyncConfig = AsyncApiTestConfig().apply {
            put("inMemoryBroker", mapOf("host" to "async-host", "port" to 9400))
        }

        val serviceConfig = CommonServiceConfig<TestRunOptions, TestSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(
                            SpecificationDefinition.StringValue(openApiSpecFile.name),
                            SpecificationDefinition.StringValue(asyncApiSpecFile.name)
                        )
                    )
                )
            ),
            runOptions = RefOrValue.Value(
                TestRunOptions(
                    openapi = OpenApiTestConfig(baseUrl = "http://openapi-host:9401"),
                    asyncapi = asyncConfig
                )
            )
        )

        val entriesByPath = TestServiceConfig(service = RefOrValue.Value(serviceConfig))
            .getSpecificationSources(resolver, null)
            .values
            .flatten()
            .associateBy { it.specPathInConfig }

        assertThat(entriesByPath[openApiSpecFile.name]?.baseUrl).isEqualTo("http://openapi-host:9401")
        assertThat(entriesByPath[asyncApiSpecFile.name]?.baseUrl).isEqualTo("async-host:9400")
    }
}
