package io.specmatic.conversions

import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.pattern.parsedJSONObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class ObjectPropertyBoundsParseTest {
    @ParameterizedTest
    @CsvSource("0 | 0 properties", "1 | 1 property", "2 | 2 properties", delimiter = '|')
    fun `closed capacity diagnostics use correct count grammar`(declaredCount: Int, expected: String) {
        val properties = when (declaredCount) {
            0 -> "{}"
            1 -> "{ id: { type: string } }"
            else -> "{ id: { type: string }, name: { type: string } }"
        }
        val schema = """
type: object
minProperties: 3
maxProperties: 4
additionalProperties: false
properties: $properties
        """.trimIndent()

        val (_, diagnostics) = OpenApiSpecification.fromYAML(specificationWithObject(schema), "bounds.yaml").toFeatureLenient()

        assertThat(diagnostics.reportString()).contains("declared capacity of $expected").doesNotContain("1 properties")
    }

    @Test
    fun `conflicts in additional property schemas are reported at their nested field and recovered`() {
        val schema = """
type: object
minProperties: 2
maxProperties: 2
required: [id]
properties:
  id:
    type: string
additionalProperties:
  type: object
  minProperties: 2
  maxProperties: 1
  additionalProperties:
    type: integer
        """.trimIndent()

        val (feature, diagnostics) = OpenApiSpecification.fromYAML(specificationWithObject(schema), "bounds.yaml").toFeatureLenient()

        assertThat(diagnostics.toIssues()).hasSize(1)
        assertThat(diagnostics.reportString()).contains("components.schemas.Bounded.additionalProperties.maxProperties", "OAS0007")
        val scenario = feature.scenarios.single()
        val request = scenario.generateHttpRequest()
        val outer = request.body as JSONObjectValue
        assertThat(outer.jsonObject).hasSize(2)
        val inner = outer.jsonObject.filterKeys { it != "id" }.values.single() as JSONObjectValue
        assertThat(inner.jsonObject).hasSize(2)
        assertThat(scenario.httpRequestPattern.matches(request, scenario.resolver).isSuccess()).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "-1 | false | {} | {\"id\": \"x\"}",
        "1 | true | {\"id\": \"x\", \"name\": \"y\"} | {\"id\": \"x\", \"name\": \"y\", \"extra\": \"z\"}",
        delimiter = '|'
    )
    fun `invalid maxima are reported and recovered to accommodate required properties`(
        maximum: Int, required: Boolean, valid: String, invalid: String
    ) {
        val schema = """
type: object
minProperties: 0
maxProperties: $maximum
additionalProperties: true
required: ${if (required) "[id, name]" else "[]"}
properties:
  id:
    type: string
  name:
    type: string
        """.trimIndent()

        val (feature, diagnostics) = OpenApiSpecification.fromYAML(specificationWithObject(schema), "bounds.yaml").toFeatureLenient()

        assertThat(diagnostics.toIssues()).hasSize(1)
        assertThat(diagnostics.reportString()).contains("components.schemas.Bounded.maxProperties", "OAS0007", "maxProperties $maximum")
        val scenario = feature.scenarios.single()
        val request = scenario.generateHttpRequest()
        assertThat(scenario.httpRequestPattern.matches(request, scenario.resolver).isSuccess()).isTrue()
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = parsedJSONObject(valid)), scenario.resolver).isSuccess()).isTrue()
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = parsedJSONObject(invalid)), scenario.resolver).isSuccess()).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        "-1 | true | {} | {\"id\": 42}",
        "3 | false | {\"id\": \"x\", \"name\": \"y\"} | {\"id\": \"x\"}",
        delimiter = '|'
    )
    fun `invalid minima are reported and recovered without losing the object schema`(
        minimum: Int, additional: Boolean, valid: String, invalid: String
    ) {
        val schema = """
type: object
minProperties: $minimum
maxProperties: 4
additionalProperties: $additional
properties:
  id:
    type: string
  name:
    type: string
        """.trimIndent()

        val (feature, diagnostics) = OpenApiSpecification.fromYAML(specificationWithObject(schema), "bounds.yaml").toFeatureLenient()

        assertThat(diagnostics.toIssues()).hasSize(1)
        assertThat(diagnostics.reportString()).contains("components.schemas.Bounded.minProperties", "OAS0006", "minProperties $minimum")
        val scenario = feature.scenarios.single()
        val request = scenario.generateHttpRequest()
        assertThat(scenario.httpRequestPattern.matches(request, scenario.resolver).isSuccess()).isTrue()
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = parsedJSONObject(valid)), scenario.resolver).isSuccess()).isTrue()
        assertThat(scenario.httpRequestPattern.matches(request.copy(body = parsedJSONObject(invalid)), scenario.resolver).isSuccess()).isFalse()
    }

    @Test
    fun `a maximum below the minimum is reported at conversion and recovered for generation`() {
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
                          ${'$'}ref: '#/components/schemas/Bounded'
                  responses:
                    '200':
                      description: OK
            components:
              schemas:
                Bounded:
                  type: object
                  minProperties: 3
                  maxProperties: 2
                  additionalProperties:
                    type: string
                  required: [id]
                  properties:
                    id:
                      type: string
        """.trimIndent()

        val (feature, diagnostics) = OpenApiSpecification.fromYAML(specification, "bounds.yaml").toFeatureLenient()

        assertThat(diagnostics.toIssues()).hasSize(1)
        assertThat(diagnostics.reportString()).contains(
            "components.schemas.Bounded.maxProperties", "OAS0007", "maxProperties 2 cannot be less than 3"
        )
        val scenario = feature.scenarios.single()
        val request = scenario.generateHttpRequest()
        assertThat((request.body as JSONObjectValue).jsonObject).hasSize(3)
        assertThat(scenario.httpRequestPattern.matches(request, scenario.resolver).isSuccess()).isTrue()
    }

    private fun specificationWithObject(schema: String): String = """
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
              ${'$'}ref: '#/components/schemas/Bounded'
      responses:
        '200':
          description: OK
components:
  schemas:
    Bounded:
${schema.prependIndent("      ")}
    """.trimIndent()
}
