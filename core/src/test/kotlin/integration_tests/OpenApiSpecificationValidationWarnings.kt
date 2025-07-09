package integration_tests

import io.specmatic.conversions.OpenApiSpecification
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

        assertThat(stdout).contains("WARNING: The specification contains an empty media type definition for GET /test -> 200 (application/json).RESPONSE.BODY. It will be treated as a free form JSON object when generating tests, in mocks, etc. Thus, any JSON object will satisfy the requirements of this schema, and you will lose feedback about broken consumer expectations. Please provide a media type with a schema.")
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

        assertThat(stdout).doesNotContain("WARNING: The specification contains an empty media type definition for GET /bar -> 200 (application/json).RESPONSE.BODY.")
    }
}