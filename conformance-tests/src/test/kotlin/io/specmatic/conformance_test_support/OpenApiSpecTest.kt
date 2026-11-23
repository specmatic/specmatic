package io.specmatic.conformance_test_support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OpenApiSpecTest {
    @TempDir lateinit var directory: File

    @Test
    fun `large specifications retain conformance operations`() {
        val file = directory.resolve("large.yaml").apply {
            writeText("openapi: 3.0.3\ninfo: {title: Large API, version: '1'}\npaths:\n  /health:\n    get:\n      responses:\n        '204': {description: OK}\nx-description: " + "x".repeat(5 * 1024 * 1024))
        }
        assertThat(OpenApiSpec(file).operations).containsExactly(Operation("GET", "/health", null, 204))
    }

    @Test
    fun `external request schemas retain operations for OpenAPI versions`() {
        listOf("3.0.3", "3.1.0").forEach { version ->
            directory.resolve("item.yaml").writeText("type: object\nrequired: [id]\nproperties:\n  id: {type: integer}\n")
            val file = directory.resolve("api.yaml").apply { writeText("""
                openapi: $version
                info: {title: Items, version: '1'}
                paths:
                  /items:
                    post:
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              ${'$'}ref: './item.yaml'
                      responses:
                        '204': {description: accepted}
            """.trimIndent()) }
            val spec = OpenApiSpec(file)
            val operation = Operation("POST", "/items", "application/json", 204)
            assertThat(spec.operations).containsExactly(operation)
        }
    }
    @Test
    fun `request validation preserves integer type and required properties`() {
        val file = directory.resolve("api.yaml").apply { writeText("""
            openapi: 3.0.3
            info: {title: Items, version: '1'}
            paths:
              /items:
                post:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [id]
                          properties:
                            id: {type: integer}
                  responses:
                    '204': {description: accepted}
        """.trimIndent()) }
        val spec = OpenApiSpec(file)
        val operation = Operation("POST", "/items", "application/json", 204)
        assertThat(spec.validateRequestBody("""{"id":10}""", operation)).isEmpty()
        assertThat(spec.validateRequestBody("""{"id":"invalid"}""", operation)).isNotEmpty()
        assertThat(spec.validateRequestBody("{}", operation)).isNotEmpty()
    }

}
