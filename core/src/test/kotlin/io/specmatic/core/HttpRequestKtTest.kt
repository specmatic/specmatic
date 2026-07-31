package io.specmatic.core

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.url
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.utilities.jsonStringToValueMap
import io.specmatic.core.value.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

internal class HttpRequestKtTest {
    @Test
    fun `json body should end up as a JSONObjectValue object`() {
        val jsonRequest = """
            {
                "method": "POST",
                "path": "/",
                "body": {
                    "a": 1
                }
            }
        """.trimIndent()

        val request = requestFromJSON(jsonStringToValueMap(jsonRequest))
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/")
        assertThat(request.body).isInstanceOf(JSONObjectValue::class.java)

        val body = (request.body as JSONObjectValue).jsonObject

        val valueOfA = body.getValue("a")
        assertThat(valueOfA).isInstanceOf(NumberValue::class.java)
        assertThat(valueOfA.toStringLiteral()).isEqualTo("1")
    }

    @Test
    fun `request URL should percent encode square brackets in query parameter names`() {
        val request = HttpRequest(
            method = "GET",
            path = "/my-day-details",
            queryParametersMap = mapOf(
                "method.status" to "10",
                "errors[0].code" to "10"
            )
        )

        assertThat(request.getURL("http://localhost:19091/v1/act-bff"))
            .isEqualTo("http://localhost:19091/v1/act-bff/my-day-details?method.status=10&errors%5B0%5D.code=10")
    }

    @Test
    fun `when generating the request from JSON contentType of multipart form data should be optional`() {
        val requestStubData = parsedJSON("""
        {
            "method": "POST",
            "path": "/",
            "multipart-formdata": [
                {"name": "name", "filename": "@test.csv"}
            ]
        }
        """) as JSONObjectValue

        val request = requestFromJSON(requestStubData.jsonObject)
        assertThat(request.multiPartFormData.single().name).isEqualTo("name")
        val filePart = request.multiPartFormData.single() as MultiPartFileValue
        assertThat(filePart.filename).isEqualTo("test.csv")
    }

    @ParameterizedTest(name = "existing host header ''{0}'' should be replaced from builder url")
    @ValueSource(strings = ["Host", "host", "HOST"])
    fun `host header should be replaced regardless of casing`(existingHeaderName: String) {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
            headers.append(existingHeaderName, "some.other.host")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).hasSize(1).containsExactly("old.example.com")
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @Test
    fun `host header should be set from builder url when no host header exists`() {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).hasSize(1).containsExactly("old.example.com")
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @Test
    fun `multiple host headers should collapse into single value from builder url`() {
        val builder = HttpRequestBuilder().apply {
            url("https://old.example.com")
            headers.append("host", "a.example.com")
            headers.append("Host", "b.example.com")
            headers.append("HOST", "c.example.com")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).hasSize(1).containsExactly("old.example.com")
        assertThat(builder.url.host).isEqualTo("old.example.com")
    }

    @ParameterizedTest(name = "builder url {0} should produce Host ''{1}''")
    @MethodSource("builderHostCases")
    fun `host header should match builder url host including port and ip`(builderUrl: String, expectedHost: String) {
        val builder = HttpRequestBuilder().apply {
            url(builderUrl)
            headers.append("Host", "wrong.host")
        }

        val httpRequest = HttpRequest(path = "/test", method = "GET")
        httpRequest.buildKTORRequest(builder)
        assertThat(builder.headers.getAll("Host")).hasSize(1).containsExactly(expectedHost)
    }

    companion object {
        @JvmStatic
        fun builderHostCases() = listOf(
            Arguments.of("https://old.example.com", "old.example.com"),
            Arguments.of("https://old.example.com:8443", "old.example.com:8443"),
            Arguments.of("http://localhost", "localhost"),
            Arguments.of("http://127.0.0.1:8080", "127.0.0.1:8080")
        )
    }
}
