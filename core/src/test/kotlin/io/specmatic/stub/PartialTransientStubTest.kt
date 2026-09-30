package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class PartialTransientStubTest {
    @Test
    fun `a file-loaded partial transient example is not consumed by a request that misses its fixed body field`() {
        val example = ScenarioStub.parse(partialTransientExample())
        HttpStub(itemsApi(), listOf(example), port = freePort()).use { stub ->
            assertThat(stub.transientStubCount).isEqualTo(1)

            val unmatched = stub.client.execute(postItems(id = 99, name = "other"))
            assertThat(unmatched.status).isEqualTo(200)
            assertThat(unmatched.body.toStringLiteral()).doesNotContain("partial-hit")
            assertThat(stub.transientStubCount).isEqualTo(1)

            val matched = stub.client.execute(postItems(id = 2, name = "any value"))
            assertThat(matched.status).isEqualTo(200)
            assertThat(matched.body.toStringLiteral()).contains("partial-hit")
            assertThat(stub.transientStubCount).isZero()

            val afterExhaustion = stub.client.execute(postItems(id = 2, name = "any value"))
            assertThat(afterExhaustion.body.toStringLiteral()).doesNotContain("partial-hit")
        }
    }

    @Test
    fun `a partial transient posted to expectations is not consumed by a request that misses its fixed body field`() {
        HttpStub(itemsApi(), emptyList(), port = freePort()).use { stub ->
            val register = stub.client.execute(
                HttpRequest(
                    method = "POST",
                    path = "/_specmatic/expectations",
                    body = parsedJSONObject(partialTransientExample()),
                )
            )
            assertThat(register.status).isEqualTo(200)
            assertThat(stub.transientStubCount).isEqualTo(1)

            val unmatched = stub.client.execute(postItems(id = 99, name = "other"))
            assertThat(unmatched.status).isEqualTo(200)
            assertThat(unmatched.body.toStringLiteral()).doesNotContain("partial-hit")
            assertThat(stub.transientStubCount).isEqualTo(1)

            val matched = stub.client.execute(postItems(id = 2, name = "any value"))
            assertThat(matched.status).isEqualTo(200)
            assertThat(matched.body.toStringLiteral()).contains("partial-hit")
            assertThat(stub.transientStubCount).isZero()
        }
    }

    private fun itemsApi(): Feature = OpenApiSpecification.fromYAML(
        yamlContent = """
        openapi: 3.0.0
        info:
          title: Items API
          version: 1.0.0
        paths:
          /items:
            post:
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
                      required: [id, name]
                      properties:
                        id:
                          type: integer
                        name:
                          type: string
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: object
                        required: [source]
                        properties:
                          source:
                            type: string
        """.trimIndent(),
        openApiFilePath = "items-api.yaml"
    ).toFeature()

    private fun postItems(id: Int, name: String): HttpRequest = HttpRequest(
        method = "POST",
        path = "/items",
        headers = mapOf("Content-Type" to "application/json"),
        body = parsedJSONObject("""{"id":$id,"name":"$name"}"""),
    )

    private fun partialTransientExample(): String = """
        {
            "transient": true,
            "partial": {
                "http-request": {
                    "method": "POST",
                    "path": "/items",
                    "headers": { "Content-Type": "application/json" },
                    "body": { "id": 2 }
                },
                "http-response": {
                    "status": 200,
                    "body": { "source": "partial-hit" }
                }
            }
        }
        """.trimIndent()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
