package io.specmatic.core.pattern

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Resolver
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class OpenApiSchemaExampleTest {

    @Test
    fun `should extract schema examples from OpenAPI spec`() {
        val spec = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Test API
              version: "1.0"
            paths:
              /test:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                          properties:
                            name:
                              type: string
                            age:
                              type: integer
                          example:
                            name: "John Doe"
                            age: 30
                  responses:
                    '200':
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                              message:
                                type: string
                            example:
                              id: 123
                              message: "Success"
            """.trimIndent(), ""
        )
        
        val feature = spec.toFeature()
        
        // Generate some contract tests to see if patterns are created correctly
        val contractTests = feature.generateContractTests(emptyList())
        assertThat(contractTests.toList()).isNotEmpty()
        
        // This is just to verify the spec parses correctly
        // The actual test of examples will be in the integration test
    }
}