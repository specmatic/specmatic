package integration_tests

import io.specmatic.conversions.OpenApiLintViolations
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.pattern.ContractException
import io.specmatic.stub.captureStandardOutput
import io.specmatic.toViolationReportString
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

        assertThat(stdout).containsIgnoringWhitespaces("""
        >> paths./test.get.responses.200.content.application/json
        No schema property defined under mediaType application/json, defaulting to free-form object.
        """.trimIndent())
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

        assertThat(output).containsIgnoringWhitespaces("""
        >> paths./api/nocontent.post.requestBody.content.application/json
        No schema property defined under mediaType application/json, defaulting to free-form object.
        """.trimIndent())
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
                                  type: string
                      responses:
                        "200":
                          description: Random
                          content:
                            text/plain:
                              example: sample response
                            """.trimIndent(), ""
            ).toFeature()
        }

        assertThat(output).containsIgnoringWhitespaces("""
        >> paths./api/nocontent.post.responses.200.content.text/plain
        No schema property defined under mediaType text/plain, defaulting to string.
        """.trimIndent())
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

        assertThat(stdout).doesNotContain("Schema has both \${'$'}ref ")
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

        assertThat(stdout).containsIgnoringWhitespaces("""
        ${
            toViolationReportString(
                breadCrumb = "paths./users.post.responses.200.content.application/json",
                details = "No schema property defined under mediaType application/json, defaulting to free-form object."
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "paths./users.post.parameters[0].name",
                details = "The header parameter named \"Authorization\" for api-key security scheme named \"bearerAuth\" was explicitly re-defined as a parameter. The parameter should be removed.",
                OpenApiLintViolations.SECURITY_PROPERTY_REDEFINED
            )
        }
        ${
            toViolationReportString(
                breadCrumb = "paths./users.post.requestBody.content.application/json.schema",
                details = "This reference has sibling properties. In accordance with the OpenAPI 3.0 standard, they will be ignored. Please remove them.",
                OpenApiLintViolations.REF_HAS_SIBLINGS
            )
        }
        """.trimIndent())
    }
}
