package io.specmatic.conversions

import io.specmatic.core.Feature
import io.specmatic.core.pattern.AnyOfPattern
import io.specmatic.core.pattern.NumberPattern
import io.specmatic.core.pattern.StringPattern
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnyOfOpenApiIntegrationTest {
    @Test
    fun `should convert anyOf schema to AnyOfPattern`() {
        val openApiSpec = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /test:
                get:
                  responses:
                    200:
                      description: Success
                      content:
                        application/json:
                          schema:
                            anyOf:
                              - type: string
                              - type: number
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openApiSpec, "").toFeature()
        val scenario = feature.scenarios.first()
        val responseBody = scenario.httpResponsePattern.body

        assertThat(responseBody).isInstanceOf(AnyOfPattern::class.java)
        val anyOfPattern = responseBody as AnyOfPattern
        assertThat(anyOfPattern.pattern).hasSize(2)
        assertThat(anyOfPattern.pattern).anyMatch { it is StringPattern }
        assertThat(anyOfPattern.pattern).anyMatch { it is NumberPattern }
    }

    @Test
    fun `should handle anyOf with nullable patterns`() {
        val openApiSpec = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /test:
                get:
                  responses:
                    200:
                      description: Success
                      content:
                        application/json:
                          schema:
                            anyOf:
                              - type: string
                              - type: "null"
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openApiSpec, "").toFeature()
        val scenario = feature.scenarios.first()
        val responseBody = scenario.httpResponsePattern.body

        assertThat(responseBody).isInstanceOf(AnyOfPattern::class.java)
        val anyOfPattern = responseBody as AnyOfPattern
        assertThat(anyOfPattern.pattern).hasSize(2)
    }

    @Test
    fun `should handle complex anyOf with object schemas`() {
        val openApiSpec = """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            components:
              schemas:
                PersonName:
                  type: object
                  properties:
                    name:
                      type: string
                PersonId:
                  type: object
                  properties:
                    id:
                      type: number
            paths:
              /test:
                get:
                  responses:
                    200:
                      description: Success
                      content:
                        application/json:
                          schema:
                            anyOf:
                              - ${'$'}ref: '#/components/schemas/PersonName'
                              - ${'$'}ref: '#/components/schemas/PersonId'
        """.trimIndent()

        val feature = OpenApiSpecification.fromYAML(openApiSpec, "").toFeature()
        val scenario = feature.scenarios.first()
        val responseBody = scenario.httpResponsePattern.body

        assertThat(responseBody).isInstanceOf(AnyOfPattern::class.java)
        val anyOfPattern = responseBody as AnyOfPattern
        assertThat(anyOfPattern.pattern).hasSize(2)
    }
}