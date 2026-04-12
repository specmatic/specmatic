package io.specmatic.conversions

import io.specmatic.core.Result
import io.specmatic.core.pattern.parsedJSONObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OneOfSupport {
    @Test
    fun `oneOf two objects within a schema used in the request should be handled correctly`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths:
              /test:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Request'
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                Request:
                  oneOf:
                    - type: object
                      required:
                        - name
                        - address
                      properties:
                        name:
                          type: string
                        address:
                          type: string
                    - type: object
                      required:
                        - first_name
                        - last_name
                        - address
                      properties:
                        first_name:
                          type: string
                        last_name:
                          type: string
                        address:
                          type: object
                          required:
                            - street
                            - city
                          properties:
                            street:
                              type: string
                            city:
                              type: string
            """.trimIndent(), ""
        ).toFeature()

        val tests = specification.generateContractTestScenarios(emptyList()).toList().map { it.second.value }

        val requestBodies = listOf(
            parsedJSONObject("""{"name": "John", "address": "1st Street"}"""),
            parsedJSONObject("""{"first_name": "John", "last_name": "Doe", "address": {"street": "1st Street", "city": "New York"}}""")
        )

        assertThat(tests.zip(requestBodies)).allSatisfy { (test, requestBody) ->
            val doesRequestObjectMatchRequestPattern = test.httpRequestPattern.body.matches(requestBody, test.resolver)

            assertThat(doesRequestObjectMatchRequestPattern).isInstanceOf(Result.Success::class.java)
        }
    }
}