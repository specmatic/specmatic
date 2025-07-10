package io.specmatic.conversions

import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.Result.Success
import io.specmatic.core.pattern.AnyOfPattern
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnyOfEndToEndTest {
    private val openApiSpec = """
        openapi: 3.0.0
        info:
          title: AnyOf Test API
          version: 1.0.0
        paths:
          /data:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      anyOf:
                        - type: string
                        - type: number
                        - type: object
                          properties:
                            name:
                              type: string
                            age:
                              type: number
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

    private val objectOnlySpec = """
        openapi: 3.0.0
        info:
          title: AnyOf Object Test API
          version: 1.0.0
        paths:
          /data:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      anyOf:
                        - type: object
                          properties:
                            name:
                              type: string
                            age:
                              type: number
                          required:
                            - name
                        - type: object
                          properties:
                            id:
                              type: string
                            score:
                              type: number
                          required:
                            - id
              responses:
                200:
                  description: Success
                  content:
                    application/json:
                      schema:
                        anyOf:
                          - type: object
                            properties:
                              name:
                                type: string
                              age:
                                type: number
                            required:
                              - name
                          - type: object
                            properties:
                              id:
                                type: string
                              score:
                                type: number
                            required:
                              - id
    """.trimIndent()

    @Test
    fun `anyOf should work end-to-end for string input`() {
        val feature = OpenApiSpecification.fromYAML(openApiSpec, "").toFeature()
        val scenario = feature.scenarios.first()
        
        // Verify the request body pattern is AnyOfPattern
        assertThat(scenario.httpRequestPattern.body).isInstanceOf(AnyOfPattern::class.java)
        
        // Test with string input
        val stringRequest = HttpRequest(
            method = "POST",
            path = "/data",
            headers = mapOf("Content-Type" to "application/json"),
            body = StringValue("hello world")
        )
        
        val matchResult = scenario.httpRequestPattern.matches(stringRequest, scenario.resolver)
        assertThat(matchResult).isInstanceOf(Success::class.java)
    }

    @Test
    fun `anyOf should work end-to-end for number input`() {
        val feature = OpenApiSpecification.fromYAML(openApiSpec, "").toFeature()
        val scenario = feature.scenarios.first()
        
        // Test with number input
        val numberRequest = HttpRequest(
            method = "POST",
            path = "/data",
            headers = mapOf("Content-Type" to "application/json"),
            body = NumberValue(42)
        )
        
        val matchResult = scenario.httpRequestPattern.matches(numberRequest, scenario.resolver)
        assertThat(matchResult).isInstanceOf(Success::class.java)
    }

    @Test
    fun `anyOf should work end-to-end for object input`() {
        val feature = OpenApiSpecification.fromYAML(objectOnlySpec, "").toFeature()
        val scenario = feature.scenarios.first()
        
        // Test with first object type
        val objectRequest1 = HttpRequest(
            method = "POST",
            path = "/data",
            headers = mapOf("Content-Type" to "application/json"),
            body = JSONObjectValue(mapOf(
                "name" to StringValue("John"),
                "age" to NumberValue(30)
            ))
        )
        
        val matchResult1 = scenario.httpRequestPattern.matches(objectRequest1, scenario.resolver)
        assertThat(matchResult1).isInstanceOf(Success::class.java)
        
        // Test with second object type
        val objectRequest2 = HttpRequest(
            method = "POST",
            path = "/data",
            headers = mapOf("Content-Type" to "application/json"),
            body = JSONObjectValue(mapOf(
                "id" to StringValue("123"),
                "score" to NumberValue(95)
            ))
        )
        
        val matchResult2 = scenario.httpRequestPattern.matches(objectRequest2, scenario.resolver)
        assertThat(matchResult2).isInstanceOf(Success::class.java)
    }

    @Test
    fun `anyOf response should be generated correctly`() {
        val feature = OpenApiSpecification.fromYAML(objectOnlySpec, "").toFeature()
        val scenario = feature.scenarios.first()
        
        // Verify the response body pattern is AnyOfPattern
        assertThat(scenario.httpResponsePattern.body).isInstanceOf(AnyOfPattern::class.java)
        
        // Generate a response
        val response = scenario.generateHttpResponse(emptyMap())
        assertThat(response.status).isEqualTo(200)
        
        // The response body should be one of the object types
        val generatedValue = response.body
        val matchResult = scenario.httpResponsePattern.body.matches(generatedValue, scenario.resolver)
        assertThat(matchResult).isInstanceOf(Success::class.java)
    }
}