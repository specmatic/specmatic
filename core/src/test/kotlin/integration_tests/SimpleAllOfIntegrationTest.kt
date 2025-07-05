package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Result
import io.specmatic.core.pattern.AllOfPattern
import io.specmatic.core.pattern.parsedJSONObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimpleAllOfIntegrationTest {
    
    @Test
    fun `simple allOf without discriminators should work correctly`() {
        val specification = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.3
            info:
              title: Test
              version: 1.0.0
            paths:
              /test:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          allOf:
                            - type: object
                              properties:
                                name:
                                  type: string
                            - type: object
                              properties:
                                age:
                                  type: integer
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            """.trimIndent(), ""
        ).toFeature()

        val tests = specification.generateContractTestScenarios(emptyList()).toList().map { it.second.value }
        
        assertThat(tests).hasSize(1)
        val test = tests.first()
        
        // Verify that AllOfPattern is being used
        assertThat(test.httpRequestPattern.body).isInstanceOf(AllOfPattern::class.java)
        
        // Test that a valid object with both name and age works
        val validObject = parsedJSONObject("""{"name": "John", "age": 30}""")
        val matchResult = test.httpRequestPattern.body.matches(validObject, test.resolver)
        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }
}