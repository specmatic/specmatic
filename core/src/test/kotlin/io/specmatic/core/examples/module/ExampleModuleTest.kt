package io.specmatic.core.examples.module

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.SpecmaticConfig
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
            contractFile.parentFile.resolve("shared_examples").canonicalFile.path,
            contractFile.parentFile.resolve("mock_examples").canonicalFile.path,
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
    fun `get examples for should load from existing configured and implicit dirs and skip missing dirs`(@TempDir tempDir: File) {
        val contractFile = tempDir.resolve("api.yaml")
        val configuredDir = tempDir.resolve("configured_examples").apply { mkdirs() }
        val implicitDir = tempDir.resolve("api_examples").apply { mkdirs() }

        val configuredExample = ScenarioStub(request = HttpRequest("GET", "/orders/10"),response = HttpResponse(status = 200)).toJSON().toStringLiteral()
        configuredDir.resolve("configured.json").writeText(configuredExample)

        val implicitExample = ScenarioStub(request = HttpRequest("POST", "/orders"),response = HttpResponse(status = 201)).toJSON().toStringLiteral()
        implicitDir.resolve("implicit.json").writeText(implicitExample)

        val specmaticConfig = mockk<SpecmaticConfig>(relaxed = true).apply {
            every { getTestExampleDirs(contractFile) } returns listOf("configured_examples", "missing_examples")
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
            every { getTestExampleDirs(contractFile) } returns listOf("configured_examples")
            every { getStubExampleDirs(contractFile) } returns emptyList()
        }

        val module = ExampleModule(specmaticConfig)
        val schemaExamples = module.getSchemaExamplesFor(contractFile)

        assertThat(schemaExamples.map { it.file.name })
            .containsExactlyInAnyOrder("resource.order.example.json", "resource.customer.example.json")
    }
}
