package io.specmatic.stub

import com.sun.net.httpserver.HttpServer
import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.HttpRequest as SpecmaticRequest
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

internal class GzipMockRequestTest {
    @Test
    fun `gzip encoded JSON matches its request schema`() {
        withMock("application/json") { stub ->
            val response = sendGzip(stub, "application/json", gzip("""{"name":"Jane"}"""))
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(204)
        }
    }

    @Test
    fun `decoded JSON schema mismatch identifies the invalid field`() {
        withMock("application/json") { stub ->
            val response = sendGzip(stub, "application/json", gzip("""{"name":42}"""))
            assertThat(response.statusCode()).isEqualTo(400)
            assertThat(response.body()).contains("REQUEST.BODY.name")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `adapters see normalized headers on decoded requests`(sendContentLength: Boolean) {
        withMock("application/json") { stub ->
            val received = CompletableFuture<SpecmaticRequest>()
            stub.registerRequestInterceptor(object : RequestInterceptor {
                override val name = "capture decoded request"

                override fun interceptRequest(httpRequest: SpecmaticRequest): SpecmaticRequest? {
                    received.complete(httpRequest)
                    return null
                }
            })
            val response = sendGzip(stub, "application/json", gzip("""{"name":"Jåne"}"""), sendContentLength)

            assertThat(response.statusCode()).isEqualTo(204)
            val headers = received.get(5, TimeUnit.SECONDS).headers.mapKeys { it.key.lowercase() }
            assertThat(headers).containsEntry("content-type", "application/json").doesNotContainKey("content-encoding")
            if (sendContentLength) assertThat(headers).containsEntry("content-length", "16")
            else assertThat(headers).doesNotContainKey("content-length")
        }
    }

    @Test
    fun `truncated gzip is rejected before request adapters run`() {
        withMock("application/json") { stub ->
            val adapterRan = AtomicBoolean()
            stub.registerRequestInterceptor(object : RequestInterceptor {
                override val name = "record adapter execution"

                override fun interceptRequest(httpRequest: SpecmaticRequest): SpecmaticRequest? {
                    adapterRan.set(true)
                    return null
                }
            })
            val compressed = gzip("""{"name":"Jane"}""")
            val response = sendGzip(stub, "application/json", compressed.copyOf(compressed.size - 1))

            assertThat(response.statusCode()).isEqualTo(400)
            assertThat(response.body()).isEqualTo("Could not parse request")
            assertThat(adapterRan.get()).isFalse()
        }
    }

    @Test
    fun `gzip encoded form fields match their request schema`() {
        val contentType = "application/x-www-form-urlencoded"
        withMock(contentType) { stub ->
            val response = sendGzip(stub, contentType, gzip("name=Jane+Doe"))
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(204)
        }
    }

    @Test
    fun `gzip encoded multipart body matches its fields and file`() {
        val multipartSchema = """
            type: object
            required: [name, file]
            properties:
              name:
                type: string
              file:
                type: string
                format: binary
        """.trimIndent()
        val multipart = listOf(
            "--mock-boundary",
            "Content-Disposition: form-data; name=\"name\"",
            "",
            "Jane",
            "--mock-boundary",
            "Content-Disposition: form-data; name=\"file\"; filename=\"report.bin\"",
            "Content-Type: application/octet-stream",
            "",
            "report contents",
            "--mock-boundary--",
            ""
        ).joinToString("\r\n")

        withMock("multipart/form-data", multipartSchema) { stub ->
            val response = sendGzip(stub, "multipart/form-data; boundary=mock-boundary", gzip(multipart))
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(204)
        }
    }

    @Test
    fun `pass-through frames the body after a request adapter changes it`() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/data") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("X-Upstream-Length", exchange.requestHeaders.getFirst("Content-Length"))
            exchange.responseHeaders.add("X-Upstream-Encoding", exchange.requestHeaders.getFirst("Content-Encoding") ?: "absent")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        upstream.start()
        try {
            HttpStub(
                features = emptyList(),
                port = testPort(),
                passThroughTargetBase = "http://127.0.0.1:${upstream.address.port}"
            ).use { stub ->
                stub.registerRequestInterceptor(object : RequestInterceptor {
                    override val name = "replace report body"

                    override fun interceptRequest(httpRequest: SpecmaticRequest): SpecmaticRequest =
                        httpRequest.updateBody("""{"name":"Jane Doe"}""")
                })
                val response = sendGzip(stub, "application/json", gzip("""{"name":"Jane"}"""))

                assertThat(response.statusCode()).isEqualTo(200)
                assertThat(io.specmatic.core.pattern.parsedJSON(response.body()))
                    .isEqualTo(io.specmatic.core.pattern.parsedJSON("""{"name":"Jane Doe"}"""))
                assertThat(response.headers().firstValue("X-Upstream-Encoding")).hasValue("absent")
                assertThat(response.headers().firstValue("X-Upstream-Length"))
                    .hasValue(response.body().toByteArray(Charsets.UTF_8).size.toString())
            }
        } finally {
            upstream.stop(0)
        }
    }

    private fun withMock(
        mediaType: String,
        requestSchema: String = """
            type: object
            required: [name]
            properties:
              name:
                type: string
        """.trimIndent(),
        test: (HttpStub) -> Unit
    ) {
        val feature = OpenApiSpecification.fromYAML(
            """
            openapi: 3.0.3
            info:
              title: Gzip request API
              version: '1'
            paths:
              /data:
                post:
                  requestBody:
                    required: true
                    content:
                      $mediaType:
                        schema:
                  responses:
                    '204':
                      description: Accepted
            """.trimIndent().replace("            schema:", "            schema:\n" + requestSchema.prependIndent("              ")), ""
        ).toFeature()

        HttpStub(feature, port = testPort()).use(test)
    }

    private fun sendGzip(
        stub: HttpStub,
        contentType: String,
        bytes: ByteArray,
        sendContentLength: Boolean = true
    ): HttpResponse<String> {
        val body = if (sendContentLength) HttpRequest.BodyPublishers.ofByteArray(bytes)
        else HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
        val request = HttpRequest.newBuilder(URI.create("${stub.endPoint}/data"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", contentType)
            .header("Content-Encoding", "gzip")
            .POST(body)
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun gzip(body: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return bytes.toByteArray()
    }
}
