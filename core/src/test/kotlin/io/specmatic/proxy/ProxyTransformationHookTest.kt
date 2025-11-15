package io.specmatic.proxy

import io.specmatic.conversions.OpenApiSpecification
import io.specmatic.core.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.StringValue
import io.specmatic.stub.RequestTransformationHook
import io.specmatic.stub.ResponseTransformationHook
import io.specmatic.stub.SpecmaticConfigSource
import io.specmatic.test.HttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProxyTransformationHookTest {
    @Test
    fun `proxy should track transformed request and original response`() {
        var trackedRequest: HttpRequest? = null
        var trackedResponse: HttpResponse? = null

        val requestObserver = RequestObserver { request, response ->
            trackedRequest = request
            trackedResponse = response
        }

        // Create a simple provider server
        val providerSpec = """
openapi: 3.0.0
info:
  title: Provider API
  version: 1.0.0
paths:
  /test:
    get:
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent()

        val providerFeature = OpenApiSpecification.fromYAML(providerSpec, "").toFeature()
        val provider = io.specmatic.stub.HttpStub(
            features = listOf(providerFeature),
            host = "127.0.0.1",
            port = 9001,
            timeoutMillis = 0
        )

        try {
            // Create a request transformation hook that adds a header for tracking
            val requestHook = object : RequestTransformationHook {
                override fun transformRequest(requestJson: JSONObjectValue): JSONObjectValue {
                    val requestMap = requestJson.jsonObject["http-request"] as JSONObjectValue
                    val headers = (requestMap.jsonObject["headers"] as? JSONObjectValue)?.jsonObject ?: emptyMap()
                    val modifiedRequest = requestMap.copy(
                        jsonObject = requestMap.jsonObject.plus(
                            "headers" to JSONObjectValue(headers.plus("X-Tracked-Request" to StringValue("true")))
                        )
                    )
                    return JSONObjectValue(mapOf("http-request" to modifiedRequest))
                }
            }

            // Create a response transformation hook that adds tracking info to body
            val responseHook = object : ResponseTransformationHook {
                override fun transformResponse(requestResponseJson: JSONObjectValue): JSONObjectValue {
                    val responseMap = requestResponseJson.jsonObject["http-response"] as JSONObjectValue
                    val modifiedResponse = responseMap.copy(
                        jsonObject = responseMap.jsonObject.plus("body" to StringValue("tracked_response"))
                    )
                    return JSONObjectValue(
                        mapOf(
                            "http-request" to requestResponseJson.jsonObject["http-request"]!!,
                            "http-response" to modifiedResponse
                        )
                    )
                }
            }

            val fileWriter = FakeFileWriter()
            val proxy = Proxy(
                host = "127.0.0.1",
                port = 9002,
                baseURL = "http://127.0.0.1:9001",
                outputDirectory = fileWriter,
                requestObserver = requestObserver
            )

            // Register the hooks
            proxy.registerRequestTransformationHook(requestHook)
            proxy.registerResponseTransformationHook(responseHook)

            try {
                // Make a request through the proxy
                val client = HttpClient("http://127.0.0.1:9002")
                val request = HttpRequest(method = "GET", path = "/test")
                val response = client.execute(request)

                // Verify the response received by consumer is the original (not transformed)
                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isNotEqualTo("tracked_response")

                // Verify the tracked request was transformed
                assertThat(trackedRequest).isNotNull
                assertThat(trackedRequest?.headers).containsKey("X-Tracked-Request")
                assertThat(trackedRequest?.headers?.get("X-Tracked-Request")).isEqualTo("true")

                // Verify the tracked response was transformed
                assertThat(trackedResponse).isNotNull
                assertThat(trackedResponse?.body?.toStringLiteral()).isEqualTo("tracked_response")
            } finally {
                proxy.close()
            }
        } finally {
            provider.close()
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell scripts work differently on Windows")
    fun `proxy hooks should be loaded from configuration`(@TempDir tempDir: File) {
        var trackedRequest: HttpRequest? = null
        var trackedResponse: HttpResponse? = null

        val requestObserver = RequestObserver { request, response ->
            trackedRequest = request
            trackedResponse = response
        }

        // Create a simple provider server
        val providerSpec = """
openapi: 3.0.0
info:
  title: Provider API
  version: 1.0.0
paths:
  /test:
    get:
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent()

        val providerFeature = OpenApiSpecification.fromYAML(providerSpec, "").toFeature()
        val provider = io.specmatic.stub.HttpStub(
            features = listOf(providerFeature),
            host = "127.0.0.1",
            port = 9003,
            timeoutMillis = 0
        )

        try {
            // Shell script that adds a tracking header to request
            val requestHookScript = tempDir.resolve("request_hook.sh").apply {
                writeText("""
#!/bin/sh
cat > /dev/null
printf '{"http-request":{"method":"GET","path":"/test","headers":{"X-Tracked-Request":"true"}}}'
                """.trimIndent())
                setExecutable(true)
            }

            // Shell script that transforms response body for tracking
            val responseHookScript = tempDir.resolve("response_hook.sh").apply {
                writeText("""
#!/bin/sh
cat > /dev/null
printf '{"http-request":{"method":"GET","path":"/test"},"http-response":{"status":200,"body":"tracked_response"}}'
                """.trimIndent())
                setExecutable(true)
            }

            val specmaticConfig = SpecmaticConfig(
                hooks = mapOf(
                    "decode_request_from_consumer" to requestHookScript.absolutePath,
                    "decode_response_from_provider" to responseHookScript.absolutePath
                )
            )

            val fileWriter = FakeFileWriter()
            val proxy = Proxy(
                host = "127.0.0.1",
                port = 9004,
                baseURL = "http://127.0.0.1:9003",
                outputDirectory = fileWriter,
                requestObserver = requestObserver,
                specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig)
            )

            try {
                // Make a request through the proxy
                val client = HttpClient("http://127.0.0.1:9004")
                val request = HttpRequest(method = "GET", path = "/test")
                val response = client.execute(request)

                // Verify the response received by consumer is the original (not transformed)
                assertThat(response.status).isEqualTo(200)
                assertThat(response.body.toStringLiteral()).isNotEqualTo("tracked_response")

                // Verify the tracked request was transformed
                assertThat(trackedRequest).isNotNull
                assertThat(trackedRequest?.headers).containsKey("X-Tracked-Request")

                // Verify the tracked response was transformed
                assertThat(trackedResponse).isNotNull
                assertThat(trackedResponse?.body?.toStringLiteral()).isEqualTo("tracked_response")
            } finally {
                proxy.close()
            }
        } finally {
            provider.close()
        }
    }
}
