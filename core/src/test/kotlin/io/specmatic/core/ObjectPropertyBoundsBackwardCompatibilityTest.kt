package io.specmatic.core

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.ContractException
import io.specmatic.reporter.commands.InsightsReportOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class ObjectPropertyBoundsBackwardCompatibilityTest {
    @ParameterizedTest
    @CsvSource(
        "request, minProperties, 2, 1, true",
        "request, minProperties, 2, 3, false",
        "request, maxProperties, 2, 1, false",
        "request, maxProperties, 2, 3, true",
        "response, minProperties, 2, 1, false",
        "response, minProperties, 2, 3, true",
        "response, maxProperties, 2, 1, true",
        "response, maxProperties, 2, 3, false"
    )
    fun `property bound changes respect request and response compatibility directions`(
        location: String, bound: String, oldLimit: Int, newLimit: Int, compatible: Boolean
    ) {
        val schema = """
type: object
$bound: LIMIT
additionalProperties:
  type: string
required: [id]
properties:
  id:
    type: integer
  name:
    type: string
  active:
    type: boolean
        """.trimIndent()
        val older = OpenApiSpecification.fromYAML(
            specificationWithObject(schema.replace("LIMIT", oldLimit.toString()), location), "old.yaml"
        ).toFeature()
        val newer = OpenApiSpecification.fromYAML(
            specificationWithObject(schema.replace("LIMIT", newLimit.toString()), location), "new.yaml"
        ).toFeature()

        val result = testBackwardCompatibility(older, newer, InsightsReportOptions())

        assertThat(result.success()).withFailMessage(result.report()).isEqualTo(compatible)
    }

    @ParameterizedTest
    @CsvSource(
        "{ type: string } | { type: integer } | false",
        "{ type: integer } | { type: string } | false",
        "{ type: string } | true | true",
        "true | { type: integer } | false",
        delimiter = '|'
    )
    fun `generated additional request properties expose incompatible value schema changes`(
        oldAdditional: String, newAdditional: String, compatible: Boolean
    ) {
        val schema = """
type: object
minProperties: 2
maxProperties: 3
additionalProperties: ADDITIONAL
required: [id]
properties:
  id:
    type: string
        """.trimIndent()
        val older = OpenApiSpecification.fromYAML(
            specificationWithObject(schema.replace("ADDITIONAL", oldAdditional), "request"), "old.yaml"
        ).toFeature()
        val newer = OpenApiSpecification.fromYAML(
            specificationWithObject(schema.replace("ADDITIONAL", newAdditional), "request"), "new.yaml"
        ).toFeature()

        val result = testBackwardCompatibility(older, newer, InsightsReportOptions())

        assertThat(result.success()).withFailMessage(result.report()).isEqualTo(compatible)
    }

    @ParameterizedTest
    @CsvSource(
        "request, 3, 2, true, false",
        "response, 3, 2, true, false",
        "request, 3, 4, false, false",
        "response, 3, 4, false, false",
        "request, 0, 1, true, true",
        "response, 0, 1, true, true"
    )
    fun `strict conversion rejects impossible object counts before compatibility checking`(
        location: String, minimum: Int, maximum: Int, additional: Boolean, required: Boolean
    ) {
        val schema = """
type: object
minProperties: $minimum
maxProperties: $maximum
additionalProperties: $additional
required: ${if (required) "[id, name]" else "[]"}
properties:
  id:
    type: string
  name:
    type: string
        """.trimIndent()
        val specification = specificationWithObject(schema, location)
        val exception = assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(specification, "bounds.yaml").toFeature()
        }
        val schemaPath = when (location) {
            "request" -> "paths./objects.post.requestBody.content.application/json.schema"
            "response" -> "paths./objects.post.responses.200.content.application/json.schema"
            else -> error("Unknown object location: $location")
        }
        assertThat(exception.report()).contains(schemaPath, if (additional) "OAS0007" else "OAS0006")
    }

    @ParameterizedTest
    @ValueSource(strings = ["true", "{ type: string }", "{ type: integer }", "{ type: object, required: [code], properties: { code: { type: integer } }, additionalProperties: false }"])
    fun `an object requiring additional properties is backward compatible with itself`(additionalProperties: String) {
        val specification = """
            openapi: 3.0.3
            info:
              title: Object bounds
              version: 1.0.0
            paths:
              /objects:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          minProperties: 2
                          maxProperties: 3
                          additionalProperties: $additionalProperties
                          properties:
                            id:
                              type: string
                  responses:
                    '200':
                      description: OK
        """.trimIndent()
        val older = OpenApiSpecification.fromYAML(specification, "old.yaml").toFeature()
        val newer = OpenApiSpecification.fromYAML(specification, "new.yaml").toFeature()

        val result = testBackwardCompatibility(older, newer, InsightsReportOptions())

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
    }

    @Test
    fun `a bounded object referenced from a nested choice is backward compatible with itself`() {
        val schema = """
type: object
required: [payload]
properties:
  payload:
    oneOf:
      - type: string
      - ${'$'}ref: '#/components/schemas/Bounded'
        """.trimIndent()
        val components = """
components:
  schemas:
    Bounded:
      type: object
      minProperties: 2
      maxProperties: 3
      additionalProperties:
        type: string
        """.trimIndent()
        val specification = specificationWithObject(schema, "request") + "\n" + components
        val older = OpenApiSpecification.fromYAML(specification, "old.yaml").toFeature()
        val newer = OpenApiSpecification.fromYAML(specification, "new.yaml").toFeature()

        val result = testBackwardCompatibility(older, newer, InsightsReportOptions())

        assertThat(result.success()).withFailMessage(result.report()).isTrue()
    }

    private fun specificationWithObject(schema: String, location: String): String {
        val operation = when (location) {
            "request" -> """
requestBody:
  required: true
  content:
    application/json:
      schema:
${schema.prependIndent("        ")}
responses:
  '200':
    description: OK
            """.trimIndent()
            "response" -> """
responses:
  '200':
    description: OK
    content:
      application/json:
        schema:
${schema.prependIndent("          ")}
            """.trimIndent()
            else -> error("Unknown object location: $location")
        }
        return """
openapi: 3.0.3
info:
  title: Object bounds
  version: 1.0.0
paths:
  /objects:
    post:
${operation.prependIndent("      ")}
        """.trimIndent()
    }
}
