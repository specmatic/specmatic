package integration_tests

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Resolver
import io.specmatic.core.pattern.JSONObjectPattern
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class DebugJSONObjectPatternExampleTest {
    
    @Test
    fun `debug toJSONObjectPattern with OpenAPI`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.0
            info:
              title: Test API
              version: 1.0.0
            paths:
              /users:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required:
                            - name
                            - email
                            - age
                          properties:
                            name:
                              type: string
                            email:
                              type: string
                            age:
                              type: integer
                          example:
                            name: "John Doe"
                            email: "john@example.com"
                            age: 30
                  responses:
                    '201':
                      description: User created
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
                              message: "User created successfully"
            """.trimIndent(), ""
        ).toFeature()

        val scenario = feature.scenarios.first()
        val requestPattern = scenario.httpRequestPattern
        val bodyPattern = requestPattern.body
        
        assertThat(bodyPattern).isInstanceOf(JSONObjectPattern::class.java)
        val jsonObjectPattern = bodyPattern as JSONObjectPattern
        
        // Check if example is properly set
        assertThat(jsonObjectPattern.example).`as`("Example should not be null").isNotNull()
        
        val resolver = Resolver()
        assertThat(resolver.allowOnlyMandatoryKeysInJsonObject).`as`("allowOnlyMandatoryKeysInJsonObject should be false").isFalse()
        
        val generated = jsonObjectPattern.generate(resolver)
        
        // If example is set correctly, the generated value should contain the example data
        assertThat(generated.toStringLiteral()).contains("John Doe")
        assertThat(generated.toStringLiteral()).contains("john@example.com")
        assertThat(generated.toStringLiteral()).contains("30")
    }
}