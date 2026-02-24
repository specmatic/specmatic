package io.specmatic.core.examples.module

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.config.SpecmaticConfigVersion
import io.specmatic.core.config.v3.Data
import io.specmatic.core.config.v3.RefOrValue
import io.specmatic.core.config.v3.SpecmaticConfigV3
import io.specmatic.core.config.v3.SpecmaticConfigV3Impl
import io.specmatic.core.config.v3.components.ExampleDirectories
import io.specmatic.core.config.v3.components.services.CommonServiceConfig
import io.specmatic.core.config.v3.components.services.Definition
import io.specmatic.core.config.v3.components.services.MockServiceConfig
import io.specmatic.core.config.v3.components.services.SpecificationDefinition
import io.specmatic.core.config.v3.components.services.TestServiceConfig
import io.specmatic.core.config.v3.components.sources.SourceV3
import io.specmatic.core.value.JSONObjectValue
import io.mockk.every
import io.mockk.mockk
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class ExampleModuleTest {
    private val exampleModule = ExampleModule(SpecmaticConfig())

    @Test
    fun `get existing examples should be able to pick mutated and partial examples properly`(@TempDir tempDir: File) {
        val openApiSpec = """
          openapi: 3.0.3
          info:
            title: Sample API
            version: 1.0.0
          paths:
            /persons/{id}:
              patch:
                summary: Create an item in a category
                parameters:
                  - name: id
                    in: path
                    required: true
                    schema:
                      type: integer
                requestBody:
                  required: true
                  content:
                    application/json:
                      schema:
                        ${"$"}ref: '#/components/schemas/PersonBase'
                responses:
                  '200':
                    description: Created item
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Person'
                  '400':
                    description: Invalid request
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Error'
          components:
            schemas:
              PersonBase:
                type: object
                required:
                  - name
                  - age
                properties:
                  name:
                    type: string
                  age:
                    type: integer
              Person:
                allOf:
                  - ${"$"}ref: '#/components/schemas/PersonBase'
                  - type: object
                    required:
                      - id
                    properties:
                      id:
                        type: integer
              Error:
                type: object
                required:
                  - message
                properties:
                  message:
                    type: string
          """.trimIndent()
        val feature = OpenApiSpecification.fromYAML(openApiSpec, "api.yaml").toFeature()

        val examplesDir = File(tempDir, "api_examples").apply { mkdirs() }
        val successExamples = listOf(
            ScenarioStub(
                request = HttpRequest("PATCH", "/persons/123"),
                response = HttpResponse(status = 200)
            ),
            ScenarioStub(
                request = HttpRequest("PATCH", "/persons/abc"),
                response = HttpResponse(status = 200)
            )
        ).flatMap { it.toExamples(examplesDir) }
        val badRequestExamples = listOf(
            ScenarioStub(
                request = HttpRequest("PATCH", "/persons/123"),
                response = HttpResponse(status = 400)
            ),
            ScenarioStub(
                request = HttpRequest("PATCH", "/persons/abc"),
                response = HttpResponse(status = 400)
            )
        ).flatMap { it.toExamples(examplesDir) }

        val allExamples = exampleModule.getExamplesFromDir(examplesDir)
        val successScenarioExamples = exampleModule.getExistingExampleFiles(
            feature, feature.scenarios.first { it.status == 200 }, allExamples
        )
        val badRequestScenarioExamples = exampleModule.getExistingExampleFiles(
            feature, feature.scenarios.first { it.status == 400 }, allExamples
        )

        assertThat(successScenarioExamples).hasSize(4)
        assertThat(successScenarioExamples.map { it.first.file }).containsExactlyInAnyOrderElementsOf(successExamples)

        assertThat(badRequestScenarioExamples).hasSize(4)
        assertThat(badRequestScenarioExamples.map { it.first.file }).containsExactlyInAnyOrderElementsOf(badRequestExamples)
    }

    private fun ScenarioStub.toExamples(examplesDir: File): List<File> {
        val nonPartialExample = this.toJSON()
        val partialExample = JSONObjectValue(mapOf("partial" to nonPartialExample))
        val nonPartial = examplesDir.resolve("${UUID.randomUUID()}.json").apply { writeText(nonPartialExample.toStringLiteral()) }
        val partial = examplesDir.resolve("${UUID.randomUUID()}_partial.json").apply { writeText(partialExample.toStringLiteral()) }
        return listOf(nonPartial, partial)
    }

    @Test
    fun `get examples dir paths should include test mock and implicit dirs with de-duplication`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val absoluteExamplesDir = tempDir.resolve("absolute_examples").apply { mkdirs() }
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf("shared_examples", absoluteExamplesDir.path)
            every { getStubExampleDirs(contractFile) } returns listOf("shared_examples", "mock_examples")
        }

        val module = ExampleModule(specmaticConfig)
        val dirPaths = module.getExamplesDirPaths(contractFile).map { it.canonicalFile.path }
        assertThat(dirPaths).containsExactlyInAnyOrder(
            absoluteExamplesDir.canonicalFile.path,
            File(".").resolve("shared_examples").canonicalFile.path,
            File(".").resolve("mock_examples").canonicalFile.path,
            contractFile.parentFile.resolve("api_examples").canonicalFile.path
        )
    }

    @Test
    fun `get examples dir paths should include implicit dir when config has no examples`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("service.yaml")
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns emptyList()
            every { getStubExampleDirs(contractFile) } returns emptyList()
        }

        val module = ExampleModule(specmaticConfig)
        val dirPaths = module.getExamplesDirPaths(contractFile)
        assertThat(dirPaths).containsExactly(contractFile.parentFile.resolve("service_examples"))
    }

    @Test
    fun `get examples dir paths should keep implicit dirs before configured dirs`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf("test_examples")
            every { getStubExampleDirs(contractFile) } returns listOf("stub_examples")
        }

        val module = ExampleModule(specmaticConfig)
        val dirPaths = module.getExamplesDirPaths(contractFile).map { it.canonicalFile.path }
        val defaultExternal = contractFile.parentFile.resolve("api_examples").canonicalFile.path

        assertThat(dirPaths.first()).isEqualTo(defaultExternal)
        assertThat(dirPaths).containsExactly(
            defaultExternal,
            File(".").resolve("test_examples").canonicalFile.path,
            File(".").resolve("stub_examples").canonicalFile.path
        )
    }

    @Test
    fun `get examples for should load from existing configured and implicit dirs and skip missing dirs`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("api.yaml")
        val configuredDir = tempDir.resolve("configured_examples").apply { mkdirs() }
        val implicitDir = tempDir.resolve("api_examples").apply { mkdirs() }

        val configuredExample = ScenarioStub(request = HttpRequest("GET", "/orders/10"),response = HttpResponse(status = 200)).toJSON().toStringLiteral()
        configuredDir.resolve("configured.json").writeText(configuredExample)

        val implicitExample = ScenarioStub(request = HttpRequest("POST", "/orders"),response = HttpResponse(status = 201)).toJSON().toStringLiteral()
        implicitDir.resolve("implicit.json").writeText(implicitExample)

        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf(configuredDir.absolutePath, "missing_examples")
            every { getStubExampleDirs(contractFile) } returns emptyList()
        }

        val module = ExampleModule(specmaticConfig)
        val loadedExamples = module.getExamplesFor(contractFile).map { it.file.name }

        assertThat(loadedExamples).containsExactlyInAnyOrder("configured.json", "implicit.json")
    }

    @Test
    fun `get schema examples for should accumulate examples across configured and implicit dirs`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("api.yaml")
        val configuredDir = tempDir.resolve("configured_examples").apply { mkdirs() }
        val implicitDir = tempDir.resolve("api_examples").apply { mkdirs() }

        configuredDir.resolve("resource.order.example.json").writeText("""{"id": 10}""")
        implicitDir.resolve("resource.customer.example.json").writeText("""{"id": 20}""")

        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf(configuredDir.absolutePath)
            every { getStubExampleDirs(contractFile) } returns emptyList()
        }

        val module = ExampleModule(specmaticConfig)
        val schemaExamples = module.getSchemaExamplesFor(contractFile)

        assertThat(schemaExamples.map { it.file.name })
            .containsExactlyInAnyOrder("resource.order.example.json", "resource.customer.example.json")
    }

    @Test
    fun `getFirstExampleDir should return common dir when paths match via canonicalization relative vs absolute`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val shared = tempDir.resolve("shared_examples").apply { mkdirs() }
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf(shared.path)
            every { getStubExampleDirs(contractFile) } returns listOf(shared.canonicalPath)
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        assertThat(dir.canonicalFile).isEqualTo(shared.canonicalFile)
    }

    @Test
    fun `getFirstExampleDir should treat dot-segment paths as common via canonicalization`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val shared = tempDir.resolve("shared_examples").apply { mkdirs() }
        val testPath = shared.path // .../shared_examples
        val stubPath = shared.parentFile.resolve("./shared_examples").path // ..././shared_examples
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf(testPath)
            every { getStubExampleDirs(contractFile) } returns listOf(stubPath)
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        assertThat(dir.canonicalFile).isEqualTo(shared.canonicalFile)
    }

    @Test
    fun `getFirstExampleDir should pick earliest common by test order when multiple commons exist`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val common1 = tempDir.resolve("common_1").apply { mkdirs() }
        val common2 = tempDir.resolve("common_2").apply { mkdirs() }
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf(tempDir.resolve("test_only").path, common2.path, common1.path)
            every { getStubExampleDirs(contractFile) } returns listOf(common1.path, common2.path, tempDir.resolve("stub_only").path)
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        assertThat(dir.canonicalFile).isEqualTo(common2.canonicalFile)
    }

    @Test
    fun `getFirstExampleDir should return first test examples dir when present even if stub dirs exist`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf("test_examples_1", "test_examples_2")
            every { getStubExampleDirs(contractFile) } returns listOf("stub_examples_1")
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        assertThat(dir).isEqualTo(File("test_examples_1"))
    }

    @Test
    fun `getFirstExampleDir should return first stub examples dir when no test dirs exist`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("api.yaml")
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns emptyList()
            every { getStubExampleDirs(contractFile) } returns listOf("stub_examples_1", "stub_examples_2")
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        assertThat(dir).isEqualTo(File("stub_examples_1"))
    }

    @Test
    fun `getFirstExampleDir should not pick test examples of another spec`(@TempDir tempDir: File) {
        val sutSpec = tempDir.resolve("sut-only.yaml").apply { writeText("openapi: 3.0.0") }
        val dependencySpec = tempDir.resolve("dependency-only.yaml").apply { writeText("openapi: 3.0.0") }
        val sutTestExamplesDir = tempDir.resolve("sut_test_examples").apply { mkdirs() }
        val dependencyStubExamplesDir = tempDir.resolve("dependency_stub_examples").apply { mkdirs() }
        val source = SourceV3.create(filesystem = SourceV3.FileSystem(directory = tempDir.canonicalPath))

        val sut = TestServiceConfig(
            service = RefOrValue.Value(
                CommonServiceConfig(
                    definitions = listOf(
                        Definition(
                            Definition.Value(
                                source = RefOrValue.Value(source),
                                specs = listOf(SpecificationDefinition.StringValue(sutSpec.name))
                            )
                        )
                    ),
                    data = Data(
                        examples = RefOrValue.Value(
                            listOf(RefOrValue.Value(ExampleDirectories(directories = listOf(sutTestExamplesDir.canonicalPath))))
                        )
                    )
                )
            )
        )

        val dependencies = MockServiceConfig(
            services = listOf(
                MockServiceConfig.Value(
                    service = RefOrValue.Value(
                        CommonServiceConfig(
                            definitions = listOf(
                                Definition(
                                    Definition.Value(
                                        source = RefOrValue.Value(source),
                                        specs = listOf(SpecificationDefinition.StringValue(dependencySpec.name))
                                    )
                                )
                            ),
                            data = Data(
                                examples = RefOrValue.Value(
                                    listOf(RefOrValue.Value(ExampleDirectories(directories = listOf(dependencyStubExamplesDir.canonicalPath))))
                                )
                            )
                        )
                    )
                )
            )
        )

        val specmaticConfig = SpecmaticConfigV3Impl(
            file = tempDir.resolve("specmatic.yaml"),
            specmaticConfig = SpecmaticConfigV3(version = SpecmaticConfigVersion.VERSION_3, systemUnderTest = sut, dependencies = dependencies)
        )

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(dependencySpec)
        assertThat(dir.canonicalFile).isEqualTo(dependencyStubExamplesDir.canonicalFile)
    }

    @Test
    fun `getFirstExampleDir should return implicit examples dir when config has no dirs`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("contracts").apply { mkdirs() }.resolve("service.yaml")
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns emptyList()
            every { getStubExampleDirs(contractFile) } returns emptyList()
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        val expectedImplicit = contractFile.absoluteFile.parentFile.resolve("service_examples")
        assertThat(dir.canonicalFile).isEqualTo(expectedImplicit.canonicalFile)
    }

    @Test
    fun `getFirstExampleDir should not require configured dir to exist`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("api.yaml")
        val missingDir = tempDir.resolve("does_not_exist").path
        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf(missingDir)
            every { getStubExampleDirs(contractFile) } returns emptyList()
        }

        val module = ExampleModule(specmaticConfig)
        val dir = module.getFirstExampleDir(contractFile)
        assertThat(dir.path).isEqualTo(File(missingDir).path)
        assertThat(dir.exists()).isFalse()
    }
}
