package io.specmatic.conformance_test_support

import io.swagger.v3.parser.OpenAPIV3Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class OperationsTest {

    @ParameterizedTest(name = "GET {1} matches pattern {0}")
    @CsvSource(
        "/users,                         /users",
        "/users/{id},                    /users/42",
        "/users/{id}/posts,              /users/42/posts",
        "/users/{userId}/posts/{postId}, /users/1/posts/99",
        "/images/{imageId}.png,          /images/abc.png",
        "/items/item-{itemId},           /items/item-7",
        "/widgets/{id}-v{version},       /widgets/3-v2",
        "'/files/{f1},{f2}',             '/files/a,b'",
        "/reports/report-{id}-summary,   /reports/report-5-summary",
        "/coordinates/{lat}:{lon}:{alt}, /coordinates/1.5:2.3:100",
    )
    fun `should match parameterized paths`(pattern: String, actualPath: String) {
        val specOps = setOf(Operation("GET", pattern))
        val exchanges = listOf(exchange("GET", "http://localhost:9000$actualPath"))

        assertThat(exchanges.toOperations(specOps))
            .containsExactly(Operation("GET", pattern))
    }

    @ParameterizedTest(name = "GET {1} should not match pattern {0}")
    @CsvSource(
        "/users/{id},           /users",
        "/users/{id},           /users/42/extra",
        "/users,                /other",
        "/images/{imageId}.png, /images/abc.jpg",
        "/items/item-{itemId},  /items/thing-7",
    )
    fun `should not match incorrect paths`(pattern: String, actualPath: String) {
        val specOps = setOf(Operation("GET", pattern))
        val exchanges = listOf(exchange("post", "http://localhost:9000$actualPath"))
        val exchangeOps = exchanges.toOperations(specOps)

        assertThat(exchangeOps).doesNotContain(Operation("GET", pattern))
        assertThat(exchangeOps - specOps).containsExactly(Operation("POST", actualPath))
    }

    @Test
    fun `should strip trailing slash from request path`() {
        val specOps = setOf(Operation("GET", "/users"))

        assertThat(listOf(exchange("GET", "http://localhost:9000/users/")).toOperations(specOps))
            .containsExactly(Operation("GET", "/users"))
    }

    @Test
    fun `toOperations on OpenAPI returns all defined operations`() {
        val yaml = """
            openapi: 3.0.3
            info:
              title: Test
              version: "1.0"
            paths:
              /a:
                get:
                  operationId: get-a
                  responses:
                    '200':
                      description: OK
              /b:
                post:
                  operationId: post-b
                  responses:
                    '200':
                      description: OK
        """.trimIndent()

        val spec = OpenAPIV3Parser().readContents(yaml).openAPI

        assertThat(spec.toOperations())
            .containsExactlyInAnyOrder(Operation("GET", "/a"), Operation("POST", "/b"))
    }

    private fun exchange(method: String, url: String) = HttpExchange(
        method = method,
        url = url,
        requestHeaders = emptyMap(),
        requestBody = "",
        statusCode = 200,
        responseHeaders = emptyMap(),
        responseBody = "",
    )
}
