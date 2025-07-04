package io.specmatic.conversions

import io.specmatic.stub.captureStandardOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiSpecificationToSpecmaticPattern {

    @Test
    fun `should log warning when ref and type both are co-exists`() {
        val spec = """
            openapi: 3.0.0
            info:
              title: Employees
              version: '1.0'
            servers: []
            paths:
              '/employees':
                post:
                  summary: ''
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Employee'
                          type: object
                          required:
                            - name
                            - department
                            - designation
                          properties:
                            name:
                              type: string
                            department:
                              type: string
                            designation:
                              type: string
                  responses:
                    '201':
                      description: Employee Created Response
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id:
                                type: integer
                            required:
                              - id
            components:
              schemas:
                Employee:
                  type: object
                  required:
                    - name
                    - department
                    - designation
                  properties:
                    name:
                      type: string
                    department:
                      type: string
                    designation:
                      type: string
        """.trimIndent()

        val (output, _) = captureStandardOutput { OpenApiSpecification.fromYAML(spec, "").toFeature()  }
        assertThat(output).contains("WARNING: Schema: Employee with \$ref: #/components/schemas/Employee exists side-by-side with a neighboring object. As per the OpenAPI specification format, when both are present, only \$ref will be used when generating tests, mock responses, etc, and the neighboring type will be ignored.")
    }

}