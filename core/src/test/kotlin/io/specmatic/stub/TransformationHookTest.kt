package io.specmatic.stub

import io.specmatic.core.*
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.NumberValue
import io.specmatic.core.value.StringValue
import io.specmatic.test.LegacyHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

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
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell commands work differently on Windows")
    fun `request transformation hook should work with shell command`() {
        val gherkin = """
            Feature: API
            Scenario: API
                When GET /original
                Then status 200
                And response-body "original response"
        """.trimIndent()

        // Command that transforms the path from /original to /transformed
        val transformCommand = """
            cat | jq '.["http-request"].path = "/transformed"'
        """.trimIndent()

        val feature = parseGherkinStringToFeature(gherkin)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf("transform_stub_request" to transformCommand)
        )

        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            // The hook should transform /original to /transformed
            // Since /transformed doesn't match the spec, we expect a 400
            val request = HttpRequest(method = "GET", path = "/original")
            val response = LegacyHttpClient(stub.endPoint).execute(request)

            // Since the path gets transformed to /transformed, it won't match /original in the spec
            // So we'll get a contract mismatch
            assertThat(response.status).isNotEqualTo(200)
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS, disabledReason = "Shell commands work differently on Windows")
    fun `response transformation hook should work with shell command`() {
        val gherkin = """
            Feature: API
            Scenario: API
                When GET /test
                Then status 200
                And response-body "original"
        """.trimIndent()

        // Command that transforms the response body
        val transformCommand = """
            cat | jq '.["http-response"].body = "transformed"'
        """.trimIndent()

        val feature = parseGherkinStringToFeature(gherkin)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf("transform_stub_response" to transformCommand)
        )

        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            val request = HttpRequest(method = "GET", path = "/test")
            val response = LegacyHttpClient(stub.endPoint).execute(request)

            assertThat(response.status).isEqualTo(200)
            assertThat(response.body.toStringLiteral()).isEqualTo("transformed")
        }
    }

    @Test
    fun `hooks should be loaded from configuration`() {
        val gherkin = """
            Feature: API
            Scenario: API
                When GET /test
                Then status 200
        """.trimIndent()

        val feature = parseGherkinStringToFeature(gherkin)
        val specmaticConfig = SpecmaticConfig(
            hooks = mapOf(
                "transform_stub_request" to "echo '{\"http-request\":{\"method\":\"GET\",\"path\":\"/test\"}}'",
                "transform_stub_response" to "cat"
            )
        )

        // Just verify that the stub starts without errors when hooks are configured
        HttpStub(
            features = listOf(feature),
            specmaticConfigSource = SpecmaticConfigSource.fromConfig(specmaticConfig),
            timeoutMillis = 0
        ).use { stub ->
            assertThat(stub).isNotNull
        }
    }
}
