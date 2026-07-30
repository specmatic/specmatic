package io.specmatic.stub

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.MultiPartContentValue
import io.specmatic.core.SPECMATIC_TYPE_HEADER
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ServerSocket

internal class MultipartFilenameHttpStubTest {
    @Test
    fun `request filename prevents match with example without filename then non-strict stub falls back to spec`() {
        val example = ScenarioStub(
            request = request(filename = null),
            response = HttpResponse.ok("example response"),
        )

        HttpStub(feature(), listOf(example), port = availablePort()).use { stub ->
            val response = stub.client.execute(request(filename = "data.txt"))

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).isNotEqualTo("example response")
            assertThat(response.headers[SPECMATIC_TYPE_HEADER]).isEqualTo("random")
        }
    }

    @Test
    fun `missing request filename prevents match with example having filename then non-strict stub falls back to spec`() {
        val example = ScenarioStub(
            request = request(filename = "data.txt"),
            response = HttpResponse.ok("example response"),
        )

        HttpStub(feature(), listOf(example), port = availablePort()).use { stub ->
            val response = stub.client.execute(request(filename = null))

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).isNotEqualTo("example response")
            assertThat(response.headers[SPECMATIC_TYPE_HEADER]).isEqualTo("random")
        }
    }

    @Test
    fun `application json multipart content matches object schema in mock`() {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.1
            info:
              title: JSON multipart API
              version: "1"
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          required: [data]
                          properties:
                            data:
                              type: object
                              required: [name]
                              properties:
                                name:
                                  type: string
                        encoding:
                          data:
                            contentType: application/json
                  responses:
                    "200":
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            """.trimIndent(),
            "",
        ).toFeature()
        val request = HttpRequest(
            method = "POST",
            path = "/data",
            multiPartFormData = listOf(
                MultiPartContentValue(
                    name = "data",
                    content = parsedJSONObject("""{"name":"Jane"}"""),
                    specifiedContentType = "application/json",
                ),
            ),
        )

        HttpStub(feature, port = availablePort()).use { stub ->
            val response = stub.client.execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.headers[SPECMATIC_TYPE_HEADER]).isEqualTo("random")
        }
    }

    private fun request(filename: String?): HttpRequest =
        HttpRequest(
            method = "POST",
            path = "/data",
            multiPartFormData = listOf(
                MultiPartContentValue(
                    name = "data",
                    content = StringValue("hello"),
                    specifiedContentType = "text/plain",
                    filename = filename,
                ),
            ),
        )

    private fun feature() =
        OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.1
            info:
              title: Multipart API
              version: "1"
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      multipart/form-data:
                        schema:
                          type: object
                          required: [data]
                          properties:
                            data:
                              type: string
                  responses:
                    "200":
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            """.trimIndent(),
            "",
        ).toFeature()

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
}
