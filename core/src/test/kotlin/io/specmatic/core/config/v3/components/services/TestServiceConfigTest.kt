package io.specmatic.core.config.v3.components.services

import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.RefOrValueResolver
import io.specmatic.core.config.v3.components.runOptions.AsyncApiTestConfig
import io.specmatic.core.config.v3.components.runOptions.TestRunOptions
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
        val specFile = tempDir.resolve("contract.yaml").apply { writeText("asycnapi: 3.0.0") }
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
}
