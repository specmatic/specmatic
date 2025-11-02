package io.specmatic.conversions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.specmatic.core.utilities.yamlMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiSpecPreProcessorTest {
    @Test
    fun `should not alter additionalProperties inside examples`() {
        val yaml = """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    get:
      responses:
        '200':
          description: OK
          content:
            application/json:
              schema:
                type: object
              examples:
                sample:
                  value:
                    additionalProperties:
                      nested: true
components:
  schemas:
    NonObject:
      type: string
      additionalProperties: {}
""".trimIndent()

        val processedYaml = OpenApiSpecPreProcessor().process(yaml)
        val mapper = ObjectMapper(YAMLFactory())
        val processedRoot = mapper.readTree(processedYaml)

        val processedSchemaNode = processedRoot.path("components").path("schemas").path("NonObject")
        assertThat(processedSchemaNode.has("additionalProperties")).isFalse()
        assertThat(processedSchemaNode.path("type").textValue()).isEqualTo("string")

        val exampleNode = processedRoot
            .path("paths")
            .path("/ping")
            .path("get")
            .path("responses")
            .path("200")
            .path("content")
            .path("application/json")
            .path("examples")
            .path("sample")
            .path("value")

        assertThat(exampleNode.isMissingNode).isFalse()
        assertThat(exampleNode.has("additionalProperties")).isTrue()
    }

    @Test
    fun `should escape requestBodies under links and add ESCAPED for later parsing`() {
        val yaml = """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    get:
      responses:
        '200':
          description: OK
          links:
            LinkName:
              requestBody:
                name: John Doe
                age: 20
""".trimIndent()

        val processedYaml = OpenApiSpecPreProcessor().process(yaml)
        val processedRoot = yamlMapper.readTree(processedYaml)
        val exampleNode = processedRoot
            .path("paths")
            .path("/ping")
            .path("get")
            .path("responses")
            .path("200")
            .path("links")
            .path("LinkName")
            .path("requestBody")

        assertThat(exampleNode.isTextual).withFailMessage("Expected TextualNode got ${exampleNode.javaClass.simpleName}").isTrue
        assertThat(exampleNode.textValue()).startsWith("<ESCAPED>")
    }

    @Test
    fun `should not escape requestBodies unless its under openApi Link`() {
        val yaml = """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    get:
      responses:
        '200':
          description: OK
          examples:
            links:
              requestBody:
                name: John Doe
                age: 20
          example:
            requestBody:
              name: John Doe
              age: 20
""".trimIndent()

        val processedYaml = OpenApiSpecPreProcessor().process(yaml)
        val processedRoot = yamlMapper.readTree(processedYaml)

        val exampleNode = processedRoot
            .path("paths")
            .path("/ping")
            .path("get")
            .path("responses")
            .path("200")
            .path("example")
            .path("requestBody")

        val linkExampleNode = processedRoot
            .path("paths")
            .path("/ping")
            .path("get")
            .path("responses")
            .path("200")
            .path("examples")
            .path("links")
            .path("requestBody")

        assertThat(exampleNode.isObject).withFailMessage("Expected ObjectNode got ${exampleNode.javaClass.simpleName}").isTrue
        assertThat(linkExampleNode.isObject).withFailMessage("Expected ObjectNode got ${exampleNode.javaClass.simpleName}").isTrue
    }

    @Test
    fun `should escape non-string parameter values under links and add ESCAPED for later parsing`() {
        val yaml = """
openapi: 3.0.0
info:
  title: Sample API
  version: 1.0.0
paths:
  /ping:
    get:
      responses:
        '200':
          description: OK
          links:
            LinkName:
              parameters:
                id: 10
                empId: "123456"
                name: John
""".trimIndent()

        val processedYaml = OpenApiSpecPreProcessor().process(yaml)
        val processedRoot = yamlMapper.readTree(processedYaml)
        val parametersNode = processedRoot
            .path("paths")
            .path("/ping")
            .path("get")
            .path("responses")
            .path("200")
            .path("links")
            .path("LinkName")
            .path("parameters")

        assertThat(parametersNode.isObject).isTrue
        assertThat(parametersNode.path("id").textValue().trim()).isEqualTo("<ESCAPED>10")
        assertThat(parametersNode.path("empId").textValue().trim()).isEqualTo("123456")
        assertThat(parametersNode.path("name").textValue().trim()).isEqualTo("John")
    }
}
