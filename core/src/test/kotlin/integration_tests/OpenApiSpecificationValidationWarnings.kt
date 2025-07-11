package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.conversions.getEmptySchemaWarning
import io.specmatic.core.pattern.ContractException
import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenApiSpecificationValidationWarnings {
    @Test
    fun `should warn if an empty json object passed in the body`() {
        val spec = """
            openapi: 3.0.3
            info:
              description: A simple API with 401 response
              title: Simple API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /test:
                get:
                  description: Test endpoint
                  operationId: test
                  responses:
                    "200":
                      description: Successful response
                      content:
                        application/json: {}
        """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).contains(
            getEmptySchemaWarning("application/json", breadCrumb = "GET /test -> 200 (application/json).RESPONSE.BODY", valueType = "free form JSON object").toLogString()
        )
    }

    @Test
    fun `show an error when examples with no mediaType is found in the request`() {
        val (output, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(
                """
                openapi: 3.0.3
                info:
                  title: My service
                  description: My service
                  version: 1.0.0
                servers:
                  - url: 'https://localhost:8080'
                paths:
                  /api/nocontent:
                    post:
                      requestBody:
                        content:
                          application/json:
                            example: test data
                      responses:
                        "204":
                          description: No response
                """.trimIndent(), ""
            ).toFeature()
        }

        assertThat(output).contains(
            getEmptySchemaWarning("application/json", breadCrumb="POST /api/nocontent (application/json).REQUEST.BODY", valueType="free form JSON object").toLogString()
        )
    }

    @Test
    fun `show an error when examples with no mediaType is found in the response`() {
        val (output, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML("""
                openapi: 3.0.3
                info:
                  title: My service
                  description: My service
                  version: 1.0.0
                servers:
                  - url: 'https://localhost:8080'
                paths:
                  /api/nocontent:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  description: The name of the entity
                      responses:
                        "200":
                          description: Random
                          content:
                            text/plain:
                              example: sample response
                            """.trimIndent(), ""
            ).toFeature()
        }

        assertThat(output).contains(
            getEmptySchemaWarning("text/plain", breadCrumb="POST /api/nocontent -> 200 (text/plain).RESPONSE.BODY", valueType="text").toLogString()
        )

    }

    @Test
    fun `should warn if response body schema is missing`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /foo:
                get:
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
            """.trimIndent()

        assertThrows<ContractException> {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }
    }

    @Test
    fun `should not warn if response body schema is present`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: API
              version: 1.0.0
            servers:
            - url: /
            paths:
              /bar:
                get:
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
            """.trimIndent()

        val (stdout, _) = captureStandardOutput {
            OpenApiSpecification.fromYAML(spec, "").toFeature()
        }

        assertThat(stdout).doesNotContain(
            getEmptySchemaWarning("application/json", breadCrumb = "GET /bar -> 200 (application/json).RESPONSE.BODY", valueType = "free form JSON object").toLogString()
        )
    }

    @Test
    fun `multiple different warnings`() {
        val spec =
            """
            openapi: 3.0.3
            info:
              title: API with Issues
              version: 1.0.0
              description: A simple OpenAPI spec demonstrating common validation issues
            
            servers:
              - url: https://api.example.com/v1
            
            components:
              securitySchemes:
                bearerAuth:
                  type: http
                  scheme: bearer
                  bearerFormat: JWT
            
              schemas:
                User:
                  type: object
                  properties:
                    id:
                      type: integer
                    name:
                      type: string
            
            security:
              - bearerAuth: []
            
            paths:
              /users:
                post:
                  summary: Create a user
                  description: Creates a new user with invalid schema reference
                  parameters:
                    - name: Authorization
                      in: header
                      required: true
                      schema:
                        type: string
                      description: Authorization token
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/User'
                          type: object
                          properties:
                            email:
                              type: string
                  responses:
                    '200':
                      description: User created successfully
                      content:
                        application/json: {}
            """.trimIndent()

        val (stdout, _) =
            captureStandardOutput {
                OpenApiSpecification.fromYAML(spec, "").toFeature()
            }

        assertThat(stdout)
            .contains("POST /users -> 200 (application/json).RESPONSE.BODY")
            .contains("Bearer Authorization")
            .contains("Authorization")
            .contains("POST /users (application/json).REQUEST.BODY")
    }
}