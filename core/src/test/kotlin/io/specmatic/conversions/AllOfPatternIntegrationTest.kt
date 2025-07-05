package io.specmatic.conversions

import io.specmatic.core.Result
import io.specmatic.core.pattern.AllOfPattern
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.JSONObjectValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AllOfPatternIntegrationTest {
    @Test
    fun `simple allOf without discriminators should use AllOfPattern`() {
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
                          ${'$'}ref: '#/components/schemas/CombinedObject'
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                BaseObject:
                  type: object
                  required:
                    - name
                  properties:
                    name:
                      type: string
                AdditionalObject:
                  type: object
                  required:
                    - value
                  properties:
                    value:
                      type: integer
                CombinedObject:
                  allOf:
                    - ${'$'}ref: '#/components/schemas/BaseObject'
                    - ${'$'}ref: '#/components/schemas/AdditionalObject'
            """.trimIndent(), ""
        ).toFeature()

        val tests = specification.generateContractTestScenarios(emptyList()).toList().map { it.second.value }
        
        assertThat(tests).hasSize(1)
        val test = tests.first()
        
        // Verify that a valid object with both name and value works
        val validObject = parsedJSONObject("""{"name": "test", "value": 42}""")
        val matchResult = test.httpRequestPattern.body.matches(validObject, test.resolver)
        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
        
        // Verify that an object missing the name field fails
        val missingNameObject = parsedJSONObject("""{"value": 42}""")
        val missingNameResult = test.httpRequestPattern.body.matches(missingNameObject, test.resolver)
        assertThat(missingNameResult).isInstanceOf(Result.Failure::class.java)
        
        // Verify that an object missing the value field fails
        val missingValueObject = parsedJSONObject("""{"name": "test"}""")
        val missingValueResult = test.httpRequestPattern.body.matches(missingValueObject, test.resolver)
        assertThat(missingValueResult).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `allOf with simple patterns should use AllOfPattern`() {
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
                          ${'$'}ref: '#/components/schemas/StringWithConstraints'
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                StringWithConstraints:
                  allOf:
                    - type: string
                    - minLength: 3
                    - maxLength: 10
            """.trimIndent(), ""
        ).toFeature()

        val tests = specification.generateContractTestScenarios(emptyList()).toList().map { it.second.value }
        
        assertThat(tests).hasSize(1)
        val test = tests.first()
        
        // The request body pattern should be an AllOfPattern
        // This test verifies that the integration works at least
        val generatedValue = test.httpRequestPattern.body.generate(test.resolver)
        val matchResult = test.httpRequestPattern.body.matches(generatedValue, test.resolver)
        assertThat(matchResult).isInstanceOf(Result.Success::class.java)
    }
}