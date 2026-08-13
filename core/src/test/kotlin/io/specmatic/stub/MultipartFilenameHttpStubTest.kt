package io.specmatic.stub

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.MultiPartContentValue
import io.specmatic.core.SPECMATIC_TYPE_HEADER
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import io.specmatic.test.HttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.writeText

internal class MultipartFilenameHttpStubTest {
    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class ExampleMatching {
        @Test
        fun `example without filename matches request without filename`() {
            val example = example(filename = null)

            HttpStub(feature(), listOf(example), port = availablePort(), strictMode = true).use { stub ->
                val response = stub.client.execute(request(filename = null))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isEqualTo("example response")
            }
        }

        @Test
        fun `example with exact filename matches only the same filename`() {
            val sourceFile = tempDir.resolve("data.txt").also { it.writeText("hello") }
            val otherFile = tempDir.resolve("other.txt").also { it.writeText("hello") }
            val example = example(filename = sourceFile.toString())

            HttpStub(feature(), listOf(example), port = availablePort(), strictMode = true).use { stub ->
                val matchingResponse = stub.client.execute(request(filename = sourceFile.toString()))
                val mismatchingResponse = stub.client.execute(request(filename = otherFile.toString()))

                assertThat(matchingResponse.status).isEqualTo(200)
                assertThat(matchingResponse.body.toStringLiteral()).isEqualTo("example response")
                assertThat(mismatchingResponse.status).isEqualTo(400)
            }
        }

        @Test
        fun `string filename example matches any present filename but not an absent filename`() {
            val example = example(filename = "(string)")

            HttpStub(feature(), listOf(example), port = availablePort(), strictMode = true).use { stub ->
                val matchingResponse = stub.client.execute(request(filename = "anything.bin"))
                val missingFilenameResponse = stub.client.execute(request(filename = null))

                assertThat(matchingResponse.status).isEqualTo(200)
                assertThat(matchingResponse.body.toStringLiteral()).isEqualTo("example response")
                assertThat(missingFilenameResponse.status).isEqualTo(400)
            }
        }
    }

    @Nested
    inner class SpecificationFallback {
        @Test
        fun `request filename prevents match with example without filename then non-strict stub falls back to spec`() {
            val example = example(filename = null)

            HttpStub(feature(), listOf(example), port = availablePort()).use { stub ->
                val response = stub.client.execute(request(filename = "data.txt"))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isNotEqualTo("example response")
                assertThat(response.headers[SPECMATIC_TYPE_HEADER]).isEqualTo("random")
            }
        }

        @Test
        fun `missing request filename prevents match with example having filename then non-strict stub falls back to spec`() {
            val example = example(filename = "data.txt")

            HttpStub(feature(), listOf(example), port = availablePort()).use { stub ->
                val response = stub.client.execute(request(filename = null))

                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isNotEqualTo("example response")
                assertThat(response.headers[SPECMATIC_TYPE_HEADER]).isEqualTo("random")
            }
        }
    }

    @Nested
    inner class MultipartContent {
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

        @Test
        fun `multipart content encoding is preserved when matching a mock expectation`() {
            val request = request(filename = null, contentEncoding = "identity")

            val example = ScenarioStub(request, HttpResponse.ok("example response"))
            HttpStub(feature(), listOf(example), port = availablePort(), strictMode = true).use { stub ->
                val response = stub.client.execute(request)
                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isEqualTo("example response")
            }
        }

        @Test
        fun `ktor request conversion preserves multipart content encoding`() {
            val port = availablePort()
            val server = embeddedServer(Netty, port = port) {
                routing {
                    post("/data") {
                        val convertedRequest = ktorHttpRequestToHttpRequest(call)
                        val convertedPart = convertedRequest.multiPartFormData.single()

                        assertThat(convertedRequest.method).isEqualTo("POST")
                        assertThat(convertedRequest.path).isEqualTo("/data")
                        assertThat(convertedPart.toJSONObject()).isEqualTo(
                            MultiPartContentValue(
                                name = "data",
                                contentEncoding = "identity",
                                content = StringValue("hello"),
                                specifiedContentType = "text/plain",
                            ).toJSONObject(),
                        )

                        call.respond(HttpStatusCode.OK)
                    }
                }
            }

            server.start(wait = false)
            try {
                HttpClient("http://localhost:$port").use { client ->
                    val response = client.execute(
                        request(filename = null, contentEncoding = "identity"),
                    )

                    assertThat(response.status).isEqualTo(200)
                }
            } finally {
                server.stop()
            }
        }
    }

    private fun example(filename: String?) =
        ScenarioStub(
            request = request(filename),
            response = HttpResponse.ok("example response"),
        )

    private fun request(filename: String?, contentEncoding: String? = null): HttpRequest =
        HttpRequest(
            method = "POST",
            path = "/data",
            multiPartFormData = listOf(
                MultiPartContentValue(
                    name = "data",
                    content = StringValue("hello"),
                    specifiedContentType = "text/plain",
                    contentEncoding = contentEncoding,
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
