package io.specmatic.core

import io.specmatic.core.HttpRequest.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import io.specmatic.core.pattern.*
import io.specmatic.mock.ScenarioStub
import io.specmatic.optionalPattern
import io.ktor.client.request.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.*
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.provider.CsvSource

internal class HttpRequestTest {
    @Test
    fun `it should serialise the request correctly`() {
        val request = HttpRequest("GET", "/", HashMap(), EmptyString, HashMap(mapOf("one" to "two")))
        val expectedString = """GET /?one=two

"""

        assertEquals(expectedString, request.toLogString(""))
    }

    @Test
    fun `when serialised to json, the request should contain form fields`() {
        val json = HttpRequest("POST", "/").copy(formFields = mapOf("Data" to "10")).toJSON()
        val value = json.jsonObject.getValue("form-fields") as JSONObjectValue
        assertThat(value.jsonObject.getValue("Data")).isEqualTo(StringValue("10"))
    }


    @Test
    fun `when serialised to json, the request should contain multiple query parameters`() {
        val queryParams = QueryParameters(listOf("key1" to "value1", "key1" to "value2", "key1" to "value3", "key2" to "value1"))
        val json = HttpRequest("POST", "/").copy(queryParams = queryParams).toJSON()
        val value = json.jsonObject.getValue("query") as JSONObjectValue
        assertThat(value.jsonObject.getValue("key1")).isEqualTo(
            JSONArrayValue(
                listOf(
                    StringValue("value1"),
                    StringValue("value2"),
                    StringValue("value3")
                )
            )
        )
        assertThat(value.jsonObject.getValue("key2")).isEqualTo(StringValue("value1")
        )
    }

    @Test
    fun `when serialised to log string, the log should contain form fields`() {
        val logString = HttpRequest("POST", "/").copy(formFields = mapOf("Data" to "10")).toLogString()

        assertThat(logString).contains("Data=10")
    }

    @Test
    fun `when converting from stub a null body should be interpreted as an empty string`() {
        val stub = """
            {
              "http-request": {
                "method": "POST",
                "path": "/",
                "body": null
              },
              
              "http-response": {
                "method": "
              }
            }
        """.trimIndent()

        assertThatThrownBy {
            val json = parsedJSON(stub) as JSONObjectValue
            requestFromJSON(json.jsonObject)
        }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `request with a nullable string should result in an Any null or string pattern`() {
        val request = HttpRequest("POST", "/", emptyMap(), parsedValue("""{"data": "(string?)"}"""))
        val requestPattern = request.toPattern()
        val body = requestPattern.body
        if (body !is JSONObjectPattern) fail("Expected json object pattern")

        val dataPattern = body.pattern.getValue("data") as DeferredPattern
        val nullableStringPattern = optionalPattern(StringPattern())

        assertThat(dataPattern.resolvePattern(Resolver())).isEqualTo(nullableStringPattern)
    }

    @Test
    fun `request with a string star should result in a string list pattern`() {
        val request = HttpRequest("POST", "/", emptyMap(), parsedValue("""{"data": "(string*)"}"""))
        val requestPattern = request.toPattern()
        val body = requestPattern.body
        if (body !is JSONObjectPattern) fail("Expected json object pattern")

        val dataPattern = body.pattern.getValue("data") as DeferredPattern
        val listOfStringsPattern = ListPattern(StringPattern())

        assertThat(dataPattern.resolvePattern(Resolver())).isEqualTo(listOfStringsPattern)
    }

    @Test
    fun `converting to pattern with request with array containing string rest should result in a string rest pattern`() {
        val request = HttpRequest("POST", "/", emptyMap(), parsedValue("""{"data": ["(string...)"]}"""))
        val requestPattern = request.toPattern()
        val body = requestPattern.body

        if (body !is JSONObjectPattern) fail("Expected json object pattern")

        val dataPattern = body.pattern.getValue("data") as JSONArrayPattern
        val deferredPattern = dataPattern.pattern.first() as DeferredPattern
        assertThat(deferredPattern.resolvePattern(Resolver())).isEqualTo(RestPattern(StringPattern()))
    }

    @Test
    fun `when request body is string question then converting to pattern should result in nullable string pattern as body`() {
        val request = HttpRequest("POST", "/", emptyMap(), parsedValue("(string?)"))
        val requestPattern = request.toPattern()
        val deferredBodyPattern = requestPattern.body as DeferredPattern
        assertThat(deferredBodyPattern.resolvePattern(Resolver())).isEqualTo(optionalPattern(StringPattern()))
    }

    @Test
    fun `when request body contains a nullable json key then converting to pattern should yield a body pattern with nullable key`() {
        val requestPattern = HttpRequest(
            "POST",
            "/",
            emptyMap(),
            parsedValue("""{"maybe?": "present"}""")
        ).toPattern()

        assertThat(
            requestPattern.body.matches(
                parsedValue("""{"maybe": "present"}"""),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
        assertThat(
            requestPattern.body.matches(
                parsedValue("""{}"""),
                Resolver()
            )
        ).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `request generated by derived gherkin should be the same as original request`() {
        val request = HttpRequest("POST", path = "/square", body = parsedValue("10"))
        val featureGherkin =
            toGherkinFeature(NamedStub("Test", ScenarioStub(request, HttpResponse.ok(NumberValue(100)))))

        val feature = parseGherkinStringToFeature(featureGherkin)
        val generatedRequest = feature.scenarios.first().generateHttpRequest()
        val columns = listOf("RequestBody")
        val examples = Examples(columns, listOf(Row(columns, listOf("10"))))

        assertThat(generatedRequest.method).isEqualTo("POST")
        assertThat(generatedRequest.path).isEqualTo("/square")
        assertThat(generatedRequest.body).isInstanceOf(StringValue::class.java)
        assertThat(feature.scenarios.single().examples.single()).isEqualTo(examples)
    }

    @Test
    fun `should replace the Host header value with the specified host name`() {
        val request = HttpRequest("POST", path = "/", headers = mapOf("Host" to "example.com")).withHost("newhost.com")
        assertThat(request.headers["Host"]).isEqualTo("newhost.com")
    }

    @Test
    fun `should exclude dynamic headers`() {
        HttpRequest(
            "POST",
            path = "/",
            headers = mapOf("Authorization" to "Bearer DummyToken")
        ).withoutDynamicHeaders().headers.let {
            assertThat(it).isEmpty()
        }
    }

    @ParameterizedTest
    @MethodSource("urlFragments")
    fun `it should handle an extra slash between base and path gracefully`(baseUrl: String?, path: String) {
        println("baseUrl: $baseUrl")
        println("path: $path")

        val url = HttpRequest("GET", path).getURL(baseUrl)

        assertThat(url).isEqualTo("http://localhost/test")
    }

    @Test
    fun `it should handle single query param`() {
        val url = HttpRequest("GET", "/", queryParametersMap = mapOf("A" to "B")).getURL("http://localhost/test")
        assertThat(url).isEqualTo("http://localhost/test?A=B")
    }

    @Test
    fun `it should handle multiple query params`() {
        val url = HttpRequest("GET", "/", queryParametersMap = mapOf("A" to "B", "C" to "D")).getURL("http://localhost/test")
        assertThat(url).isEqualTo("http://localhost/test?A=B&C=D")
    }

    @Test
    fun `it should handle URL encoding for query params`() {
        val url =
            HttpRequest("GET", "/", queryParametersMap = mapOf("A" to "B E", "©C" to "!D")).getURL("http://localhost/test")
        assertThat(url).isEqualTo("http://localhost/test?A=B+E&%C2%A9C=%21D")
    }

    @Test
    fun `override the host header to exclude the port suffix when the default port is used`() {
        val builderWithPort80 = HttpRequestBuilder().apply {
            this.url.host = "test.com"
            this.url.port = 80
        }
        HttpRequest("GET", "/").buildKTORRequest(builderWithPort80, null)
        assertThat(builderWithPort80.headers["Host"]).isEqualTo("test.com")

        val httpRequestBuilderWithHTTPS = HttpRequestBuilder().apply {
            this.url.protocol = URLProtocol.HTTPS
            this.url.host = "test.com"
            this.url.port = 443
        }
        HttpRequest("GET", "/").buildKTORRequest(httpRequestBuilderWithHTTPS, null)
        assertThat(httpRequestBuilderWithHTTPS.headers["Host"]).isEqualTo("test.com")
    }

    @Test
    fun `by default do not override the host header unless a default port is used`() {
        val httpRequestBuilder2 = HttpRequestBuilder().apply {
            this.url.host = "test.com"
            this.url.port = 8080
        }
        HttpRequest("GET", "/").buildKTORRequest(httpRequestBuilder2, null)
        assertThat(httpRequestBuilder2.headers["Host"]).isNull()
    }

    @Test
    fun `should formulate a loggable error in non-strict mode`() {
        val request = spyk(
            HttpRequest(
                "POST",
                "/test",
                headers = mapOf("SOAPAction" to "test")
            )
        )
        every { request.requestNotRecognized(any<LenientRequestNotRecognizedMessages>()) }.returns("msg")

        request.requestNotRecognized()

        verify { request.requestNotRecognized(any<LenientRequestNotRecognizedMessages>()) }
    }

    @Test
    fun `should formulate a loggable error in strict mode`() {
        val request = spyk(
            HttpRequest(
                "POST",
                "/test",
                headers = mapOf("SOAPAction" to "test")
            )
        )
        every { request.requestNotRecognized(any<StrictRequestNotRecognizedMessages>()) }.returns("msg")

        request.requestNotRecognized()

        verify { request.requestNotRecognized(any<StrictRequestNotRecognizedMessages>()) }
    }

    @Test
    fun `should formulate a loggable error message describing the SOAP request without strict mode`() {
        assertThat(
            LenientRequestNotRecognizedMessages().soap(
                "test",
                "/test"
            )
        ).isEqualTo("No matching SOAP stub or contract found for SOAPAction test and path /test")
    }

    @Test
    fun `should formulate a loggable error message describing the XML-REST request without strict mode`() {
        assertThat(
            LenientRequestNotRecognizedMessages().xmlOverHttp(
                "POST",
                "/test"
            )
        ).isEqualTo("No matching XML-REST stub or contract found for method POST and path /test")
    }

    @Test
    fun `should formulate a loggable error message describing the JSON-REST request without strict mode`() {
        assertThat(
            LenientRequestNotRecognizedMessages().restful(
                "POST",
                "/test"
            )
        ).isEqualTo("No matching REST stub or contract found for method POST and path /test")
    }

    @Test
    fun `should formulate a loggable error message describing the SOAP request in strict mode`() {
        assertThat(
            StrictRequestNotRecognizedMessages().soap(
                "test",
                "/test"
            )
        ).isEqualTo("No matching SOAP stub (strict mode) found for SOAPAction test and path /test")
    }

    @Test
    fun `should formulate a loggable error message describing the XML-REST request in strict mode`() {
        assertThat(
            StrictRequestNotRecognizedMessages().xmlOverHttp(
                "POST",
                "/test"
            )
        ).isEqualTo("No matching XML-REST stub (strict mode) found for method POST and path /test")
    }

    @Test
    fun `should formulate a loggable error message describing the JSON-REST request in strict mode`() {
        assertThat(
            StrictRequestNotRecognizedMessages().restful(
                "POST",
                "/test"
            )
        ).isEqualTo("No matching REST stub (strict mode) found for method POST and path /test")
    }

    @Test
    fun `should percent-encode spaces within path segments`() {
        val request = HttpRequest("GET", "/test path")
        assertThat(request.getURL("http://localhost")).isEqualTo("http://localhost/test%20path")
    }

    @Test
    fun `should pretty-print request body by default`() {
        val request = HttpRequest("POST", "/", body = parsedJSONObject("""{"id": 10}"""))
        assertThat(request.toLogString())
            .contains(""" "id": 10""")
            .doesNotContain("""{"id":10}""")
    }

    @Test
    fun `should print request body in one line when flag is set to false`() {
        try {
            System.setProperty(Flags.SPECMATIC_PRETTY_PRINT, "false")
            val request = HttpRequest("POST", "/", body = parsedJSONObject("""{"id": 10}"""))
            assertThat(request.toLogString())
                .contains("""{"id":10}""")
        } finally {
            System.clearProperty(Flags.SPECMATIC_PRETTY_PRINT)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "10, 10, 0",
        "(string), 10, 1",
        "10, (string), 1",
        "(string), (string), 2",
        ignoreLeadingAndTrailingWhitespace = true
    )
    fun `should calculate precision score based on the number of patterns seen`(id: String, count: String, generality: Int) {
        val request = HttpRequest("POST", "/", body = parsedJSONObject("""{"id": "$id", "count": "$count"}"""))
        assertThat(request.generality).isEqualTo(generality)
    }

    @ParameterizedTest
    @MethodSource("urlPathToExpectedPathGenerality")
    fun `should include path params to calculate generality`(path: String?, expectedGenerality: Int) {
        val request = HttpRequest(path = path)
        assertThat(request.generality).isEqualTo(expectedGenerality)
    }

    @ParameterizedTest
    @MethodSource("bodyToExpectedSpecificity")
    fun `should calculate specificity score based on the body value`(body: Value, expectedSpecificity: Int) {
        val request = HttpRequest(body = body)
        assertThat(request.bodySpecificity()).isEqualTo(expectedSpecificity)
    }

    @ParameterizedTest
    @MethodSource("urlPathToExpectedPathSpecificity")
    fun `should include path params to calculate specificity`(path: String?, expectedSpecificity: Int) {
        val request = HttpRequest(path = path)
        assertThat(request.pathSpecificity()).isEqualTo(expectedSpecificity)
    }

    @ParameterizedTest
    @MethodSource("queryParamsToExpectedSpecificity")
    fun `should calculate specificity based on query params`(queryParams: Map<String, String>, expectedSpecificity: Int) {
        val request = HttpRequest(queryParametersMap = queryParams)
        assertThat(request.queryParamsSpecificity()).isEqualTo(expectedSpecificity)
    }

    @ParameterizedTest
    @MethodSource("headersToExpectedSpecificity")
    fun `should calculate specificity based on headers`(headers: Map<String, String>, expectedSpecificity: Int) {
        val request = HttpRequest(headers = headers)
        assertThat(request.headerSpecificity()).isEqualTo(expectedSpecificity)
    }

    @ParameterizedTest
    @MethodSource("httpRequestToExpectedCombinedSpecificity")
    fun `should calculate combined specificity of HttpRequest`(
        path: String?, 
        headers: Map<String, String>, 
        queryParams: Map<String, String>, 
        body: Value, 
        expectedSpecificity: Int
    ) {
        val request = HttpRequest(
            path = path,
            headers = headers,
            queryParametersMap = queryParams,
            body = body
        )
        assertThat(request.specificity).isEqualTo(expectedSpecificity)
    }


    companion object {
        @JvmStatic
        fun urlFragments(): Stream<Arguments> =
            listOf(
                Arguments.of("http://localhost/", "/test"),
                Arguments.of("http://localhost", "test"),
                Arguments.of("http://localhost/", "test"),
                Arguments.of("http://localhost", "/test"),
                Arguments.of("", "http://localhost/test"),
                Arguments.of("http://localhost/test", ""),
                Arguments.of(null, "http://localhost/test"),
            ).stream()

        @JvmStatic
        fun urlPathToExpectedPathGenerality(): Stream<Arguments> = Stream.of(
            Arguments.of(null, 0),
            Arguments.of("", 0),
            Arguments.of("/", 0),
            Arguments.of("/persons", 0),
            Arguments.of("/(string)", 1),
            Arguments.of("/persons/1", 0),
            Arguments.of("/persons/(string)", 1),
            Arguments.of("/persons/group/1", 0),
            Arguments.of("/persons/(string)/1", 1),
            Arguments.of("/persons/(string)/1/(string)", 2),
            Arguments.of("/persons/group/(string)/1/(string)", 2),
        )

        @JvmStatic
        fun urlPathToExpectedPathSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(null, 0),
            Arguments.of("", 1),
            Arguments.of("/", 1),
            Arguments.of("/persons", 2),
            Arguments.of("/(string)", 1),
            Arguments.of("/persons/1", 3),
            Arguments.of("/persons/(string)", 2),
            Arguments.of("/persons/group/1", 4),
            Arguments.of("/persons/(string)/1", 3),
            Arguments.of("/persons/(string)/1/(string)", 3),
            Arguments.of("/persons/group/(string)/1/(string)", 4),
        )

        @JvmStatic
        fun queryParamsToExpectedSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("param1" to "value1"), 1),
            Arguments.of(mapOf("param1" to "(string)"), 0),
            Arguments.of(mapOf("param2" to "123"), 1),
            Arguments.of(mapOf("param2" to "(number)"), 0),
            Arguments.of(mapOf("param1" to "value1", "param2" to "value2"), 2),
            Arguments.of(mapOf("param1" to "(string)", "param2" to "value2"), 1),
            Arguments.of(mapOf("param1" to "value1", "param2" to "(string)"), 1),
            Arguments.of(mapOf("param1" to "(string)", "param2" to "(number)"), 0)
        )

        @JvmStatic
        fun headersToExpectedSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("Content-Type" to "application/json"), 1),
            Arguments.of(mapOf("Content-Type" to "(string)"), 0),
            Arguments.of(mapOf("Authorization" to "Bearer token123"), 1),
            Arguments.of(mapOf("Content-Type" to "application/json", "Accept" to "application/json"), 2),
            Arguments.of(mapOf("Content-Type" to "(string)", "Accept" to "application/json"), 1),
            Arguments.of(mapOf("Content-Type" to "application/json", "Accept" to "(string)"), 1),
            Arguments.of(mapOf("Content-Type" to "(string)", "Accept" to "(string)"), 0)
        )

        @JvmStatic
        fun bodyToExpectedSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(parsedJSONObject("""{"id": "10", "count": "10"}"""), 2),
            Arguments.of(parsedJSONObject("""{"id": "(string)", "count": "10"}"""), 1),
            Arguments.of(parsedJSONObject("""{"id": "10", "count": "(string)"}"""), 1),
            Arguments.of(parsedJSONObject("""{"id": "(string)", "count": "(string)"}"""), 0),

            Arguments.of(StringValue("regular string"), 1),
            Arguments.of(StringValue("(string)"), 0),

            Arguments.of(NumberValue(42), 1),
            Arguments.of(BooleanValue(true), 1),
            Arguments.of(BinaryValue("testData".toByteArray()), 1),
            Arguments.of(NullValue, 1),
            Arguments.of(JSONObjectValue(emptyMap()), 0),
            Arguments.of(JSONArrayValue(emptyList()), 0),
            Arguments.of(NoBodyValue, 0),

            Arguments.of(parsedJSONArray("""["10", "20"]"""), 2),
            Arguments.of(parsedJSONArray("""["(string)", "20"]"""), 1),
            Arguments.of(parsedJSONArray("""["10", "(string)"]"""), 1),
            Arguments.of(parsedJSONArray("""["(string)", "(string)"]"""), 0),

            Arguments.of(parsedJSONObject("""{"data": {"id": "10", "count": "10"}}"""), 2),
            Arguments.of(parsedJSONObject("""{"data": ["10", "20"]}"""), 2)
        )

        @JvmStatic
        fun httpRequestToExpectedCombinedSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "/persons/123", 
                mapOf("Content-Type" to "application/json"), 
                mapOf("param1" to "value1"), 
                StringValue("test"), 
                6
            ),

            Arguments.of(
                "/(string)", 
                mapOf("Content-Type" to "(string)"), 
                mapOf("param1" to "(string)"), 
                StringValue("(string)"), 
                1
            ),

            Arguments.of(
                "/persons/(string)", 
                mapOf("Content-Type" to "application/json", "Accept" to "(string)"), 
                mapOf("param1" to "value1", "param2" to "(string)"), 
                parsedJSONObject("""{"id": "10", "count": "(string)"}"""), 
                5
            ),

            Arguments.of(
                null, 
                emptyMap<String, String>(), 
                emptyMap<String, String>(), 
                EmptyString, 
                1
            ),

            Arguments.of(
                "/", 
                mapOf("Content-Type" to "application/json"), 
                emptyMap<String, String>(), 
                parsedJSONObject("""{"data": {"id": "10", "count": "10"}}"""), 
                4
            )
        )
    }
}
