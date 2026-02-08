package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.runOptions.AsyncApiMockConfig
import io.specmatic.core.config.v3.components.runOptions.MockRunOptions
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.config.v3.components.settings.MockSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MockServiceConfigTest {
    private val resolver = object : RefOrValueResolver {
        override fun resolveRef(reference: String): Map<*, *> {
            error("Unexpected ref resolution in test: $reference")
        }
    }

    @Test
    fun `getSpecificationSources should merge service and dependency example dirs`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("contract.txt").apply { writeText("not an openapi spec") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val dependencyData = Data(
            examples = RefOrValue.Value(
                listOf(RefOrValue.Value(ExampleDirectories(directories = listOf("dependency-examples"))))
            )
        )
        val serviceData = Data(
            examples = RefOrValue.Value(
                listOf(RefOrValue.Value(ExampleDirectories(directories = listOf("service-examples"))))
            )
        )

        val serviceConfig = CommonServiceConfig<MockRunOptions, MockSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(SpecificationDefinition.StringValue(specFile.name))
                    )
                )
            ),
            data = serviceData
        )

        val mockServiceConfig = MockServiceConfig(
            services = listOf(MockServiceConfig.Value(service = RefOrValue.Value(serviceConfig))),
            data = dependencyData,
            settings = null
        )

        val sourceEntries = mockServiceConfig.getSpecificationSources(resolver).values.flatten()

        assertThat(sourceEntries).hasSize(1)
        assertThat(sourceEntries.single().exampleDirs).containsExactlyInAnyOrder("service-examples", "dependency-examples")
    }

    @Test
    fun `getSpecificationSources should use asyncapi base url when openapi run options are absent`(@TempDir tempDir: File) {
        val specFile = tempDir.resolve("contract.txt").apply { writeText("not an openapi spec") }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val asyncApiMockConfig = AsyncApiMockConfig().apply {
            put(
                "servers", listOf(
                    mapOf(
                        "host" to "localhost",
                        "port" to 8081
                    )
                )
            )
        }

        val serviceConfig = CommonServiceConfig<MockRunOptions, MockSettings>(
            definitions = listOf(
                Definition(
                    Definition.Value(
                        source = RefOrValue.Value(source),
                        specs = listOf(SpecificationDefinition.StringValue(specFile.name))
                    )
                )
            ),
            runOptions = RefOrValue.Value(MockRunOptions(asyncapi = asyncApiMockConfig))
        )

        val mockServiceConfig = MockServiceConfig(
            services = listOf(MockServiceConfig.Value(service = RefOrValue.Value(serviceConfig))),
            settings = null
        )

        val sourceEntries = mockServiceConfig.getSpecificationSources(resolver).values.flatten()

        assertThat(sourceEntries).hasSize(1)
        assertThat(sourceEntries.single().baseUrl).isEqualTo("localhost:8081")
    }
}
