package io.specmatic.stub

import io.specmatic.core.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.HttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TransformationHookTest {
    @Test
    fun `request transformation hook adapter should transform request`() {
        // Create a simple request transformation hook that modifies the path
        val hook = object : RequestTransformationHook {
            override fun transformRequest(requestJson: JSONObjectValue): JSONObjectValue {
                val requestMap = requestJson.jsonObject["http-request"] as JSONObjectValue
                val modifiedRequest = requestMap.copy(
                    jsonObject = requestMap.jsonObject.plus("path" to StringValue("/transformed"))
                )
                return JSONObjectValue(mapOf("http-request" to modifiedRequest))
            }
        }

        val adapter = RequestTransformationHookAdapter(hook)
        val originalRequest = HttpRequest(method = "GET", path = "/original")
        val transformedRequest = adapter.interceptRequest(originalRequest)

        assertThat(transformedRequest).isNotNull
        assertThat(transformedRequest?.path).isEqualTo("/transformed")
    }

    @Test
    fun `response transformation hook adapter should transform response`() {
        // Create a simple response transformation hook that modifies the status
        val hook = object : ResponseTransformationHook {
            override fun transformResponse(requestResponseJson: JSONObjectValue): JSONObjectValue {
                val responseMap = requestResponseJson.jsonObject["http-response"] as JSONObjectValue
                val modifiedResponse = responseMap.copy(
                    jsonObject = responseMap.jsonObject.plus("status" to NumberValue(201))
                )
                return JSONObjectValue(
                    mapOf(
                        "http-request" to requestResponseJson.jsonObject["http-request"]!!,
                        "http-response" to modifiedResponse
                    )
                )
            }
        }

        val adapter = ResponseTransformationHookAdapter(hook)
        val request = HttpRequest(method = "GET", path = "/test")
        val originalResponse = HttpResponse(200, body = StringValue("original"))
        val transformedResponse = adapter.interceptResponse(request, originalResponse)

        assertThat(transformedResponse).isNotNull
        assertThat(transformedResponse?.status).isEqualTo(201)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell scripts work differently on Windows")
    fun `request transformation hook should work with shell command`(@TempDir tempDir: File) {
        val openApiSpec = """
openapi: 3.0.0
info:
  title: Test API
  version: 1.0.0
paths:
  /original:
    get:
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
  /transformed:
    get:
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent()

        val specFile = tempDir.resolve("api.yaml").apply { writeText(openApiSpec) }

        // Shell script that outputs a transformed request (changes path to /transformed)
        val transformScript = tempDir.resolve("transform_request.sh").apply {
            writeText("""
#!/bin/sh
# Ignore input and output transformed JSON with path changed to /transformed
cat > /dev/null
printf '{"http-request":{"method":"GET","path":"/transformed"}}'
            """.trimIndent())
            setExecutable(true)
        }

        val feature = parseContractFileToFeature(specFile.absolutePath)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf("decode_request_from_consumer" to transformScript.absolutePath)
        )

        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            // Request to /original gets transformed to /transformed by the hook
            val request = HttpRequest(method = "GET", path = "/original")
            val response = HttpClient(stub.endPoint).execute(request)

            // The hook transformed /original to /transformed, which matches the spec
            assertThat(response.status).isEqualTo(200)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell scripts work differently on Windows")
    fun `response transformation hook should work with shell command`(@TempDir tempDir: File) {
        val openApiSpec = """
openapi: 3.0.0
info:
  title: Test API
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

        val specFile = tempDir.resolve("api.yaml").apply { writeText(openApiSpec) }

        // Shell script that outputs transformed response with body set to "transformed"
        val transformScript = tempDir.resolve("transform_response.sh").apply {
            writeText("""
#!/bin/sh
# Ignore input and output transformed JSON with body set to "transformed"
cat > /dev/null
printf '{"http-request":{"method":"GET","path":"/test"},"http-response":{"status":200,"body":"transformed"}}'
            """.trimIndent())
            setExecutable(true)
        }

        val feature = parseContractFileToFeature(specFile.absolutePath)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf("encode_response_to_consumer" to transformScript.absolutePath)
        )

        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            val request = HttpRequest(method = "GET", path = "/test")
            val response = HttpClient(stub.endPoint).execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).isEqualTo("transformed")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell scripts work differently on Windows")
    fun `transformed request should be passed to response hook`(@TempDir tempDir: File) {
        val openApiSpec = """
openapi: 3.0.0
info:
  title: Test API
  version: 1.0.0
paths:
  /original:
    get:
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
  /transformed:
    get:
      responses:
        '200':
          description: Success
          content:
            text/plain:
              schema:
                type: string
        """.trimIndent()

        val specFile = tempDir.resolve("api.yaml").apply { writeText(openApiSpec) }
        val requestPathFile = tempDir.resolve("request_path.txt")

        // Request hook transforms path from /original to /transformed
        val requestHookScript = tempDir.resolve("request_hook.sh").apply {
            writeText("""
#!/bin/sh
cat > /dev/null
printf '{"http-request":{"method":"GET","path":"/transformed"}}'
            """.trimIndent())
            setExecutable(true)
        }

        // Response hook saves the entire input to a file to verify it received the transformed request
        val responseHookScript = tempDir.resolve("response_hook.sh").apply {
            writeText("""
#!/bin/sh
# Save input to file and output unchanged
tee ${requestPathFile.absolutePath} | cat > /dev/null
printf '{"http-request":{"method":"GET","path":"/transformed"},"http-response":{"status":200,"body":"test"}}'
            """.trimIndent())
            setExecutable(true)
        }

        val feature = parseContractFileToFeature(specFile.absolutePath)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf(
                "decode_request_from_consumer" to requestHookScript.absolutePath,
                "encode_response_to_consumer" to responseHookScript.absolutePath
            )
        )

        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            val request = HttpRequest(method = "GET", path = "/original")
            val response = HttpClient(stub.endPoint).execute(request)

            assertThat(response.status).isEqualTo(200)

            // Verify the response hook received the transformed path, not the original
            assertThat(requestPathFile).exists()
            val receivedJson = requestPathFile.readText()
            // The path should be /transformed (from the request hook), not /original
            assertThat(receivedJson).contains("/transformed")
            assertThat(receivedJson).doesNotContain("/original")
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell scripts work differently on Windows")
    fun `hooks should be loaded from configuration and execute`(@TempDir tempDir: File) {
        val openApiSpec = """
openapi: 3.0.0
info:
  title: Test API
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

        val specFile = tempDir.resolve("api.yaml").apply { writeText(openApiSpec) }
        val requestHookExecuted = tempDir.resolve("request_hook_executed.txt")
        val responseHookExecuted = tempDir.resolve("response_hook_executed.txt")

        // Request hook that writes to file when executed
        val requestHookScript = tempDir.resolve("request_hook.sh").apply {
            writeText("""
#!/bin/sh
echo "executed" > ${requestHookExecuted.absolutePath}
cat
            """.trimIndent())
            setExecutable(true)
        }

        // Response hook that writes to file when executed
        val responseHookScript = tempDir.resolve("response_hook.sh").apply {
            writeText("""
#!/bin/sh
echo "executed" > ${responseHookExecuted.absolutePath}
cat
            """.trimIndent())
            setExecutable(true)
        }

        val feature = parseContractFileToFeature(specFile.absolutePath)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf(
                "decode_request_from_consumer" to requestHookScript.absolutePath,
                "encode_response_to_consumer" to responseHookScript.absolutePath
            )
        )

        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            // Make a request to trigger the hooks
            val request = HttpRequest(method = "GET", path = "/test")
            val response = HttpClient(stub.endPoint).execute(request)

            assertThat(response.status).isEqualTo(200)

            // Verify both hooks actually executed
            assertThat(requestHookExecuted).exists()
            assertThat(requestHookExecuted.readText().trim()).isEqualTo("executed")

            assertThat(responseHookExecuted).exists()
            assertThat(responseHookExecuted.readText().trim()).isEqualTo("executed")
        }
    }
}
