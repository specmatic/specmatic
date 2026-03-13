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
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.value.*
import io.specmatic.license.core.SpecmaticProtocol
import io.specmatic.mock.MOCK_HTTP_REQUEST
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import java.io.File

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
        ).withoutTransportHeaders().headers.let {
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
        HttpRequest("GET", "/").buildKTORRequest(builderWithPort80)
        assertThat(builderWithPort80.headers["Host"]).isEqualTo("test.com")

        val httpRequestBuilderWithHTTPS = HttpRequestBuilder().apply {
            this.url.protocol = URLProtocol.HTTPS
            this.url.host = "test.com"
            this.url.port = 443
        }
        HttpRequest("GET", "/").buildKTORRequest(httpRequestBuilderWithHTTPS)
        assertThat(httpRequestBuilderWithHTTPS.headers["Host"]).isEqualTo("test.com")
    }

    @Test
    fun `should remove lowercase host header to avoid duplicates`() {
        val httpRequestBuilder = HttpRequestBuilder().apply {
            this.url.host = "target.com"
            this.url.port = 80
        }
        HttpRequest("GET", "/", headers = mapOf("host" to "original.com"))
            .buildKTORRequest(httpRequestBuilder)
        assertThat(httpRequestBuilder.headers["Host"]).isEqualTo("target.com")
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

        request.requestNotRecognizedInStrictMode()

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
        val request = HttpRequest("POST", "/", body = parsedJSONObject("""{"id": 10}"""))
        assertThat(request.toLogString(prettyPrint = false))
            .contains("""{"id":10}""")
    }

    @ParameterizedTest
    @MethodSource("pathSpecificityOrdering")
    fun `should rank paths by generality`(moreSpecific: String?, lessSpecific: String?) {
        val moreGeneral = HttpRequest(path = lessSpecific).generality
        val lessGeneral = HttpRequest(path = moreSpecific).generality
        assertThat(moreGeneral).isGreaterThan(lessGeneral)
    }

    @ParameterizedTest
    @MethodSource("bodyToExpectedSpecificity")
    fun `should calculate specificity score based on the body value`(body: Value, expectedSpecificity: Int) {
        val request = HttpRequest(body = body)
        assertThat(request.bodySpecificity()).isEqualTo(expectedSpecificity)
    }

    @ParameterizedTest
    @MethodSource("pathSpecificityOrdering")
    fun `should rank paths by specificity`(moreSpecific: String, lessSpecific: String) {
        val moreSpecificScore = HttpRequest(path = moreSpecific).pathSpecificity()
        val lessSpecificScore = HttpRequest(path = lessSpecific).pathSpecificity()
        assertThat(moreSpecificScore).isGreaterThan(lessSpecificScore)
    }

    @Test
    fun `should rank literal path over interpolated over pure parameter path in specificity`() {
        val literal = HttpRequest(path = "/items/item-123")
        val interpolated = HttpRequest(path = "/items/item-(id:string)")
        val pureParameter = HttpRequest(path = "/items/(id:string)")

        assertThat(literal.pathSpecificity()).isGreaterThan(interpolated.pathSpecificity())
        assertThat(interpolated.pathSpecificity()).isGreaterThan(pureParameter.pathSpecificity())
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
    @MethodSource("httpRequestSpecificityOrdering")
    fun `should rank http requests by combined specificity`(moreSpecific: HttpRequest, lessSpecific: HttpRequest) {
        assertThat(moreSpecific.specificity).isGreaterThan(lessSpecific.specificity)
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
        fun pathSpecificityOrdering(): Stream<Arguments> = Stream.of(
            // ----------------------------
            // Static > Dynamic
            // ----------------------------
            Arguments.of("/users/list", "/users/(id)"),
            Arguments.of("/persons/group/1", "/persons/(group)/(id)"),
            Arguments.of("/users/list/all", "/users/(id)"),

            // ----------------------------
            // Static > Mixed
            // ----------------------------
            Arguments.of("/items/item", "/items/item-(id)"),
            Arguments.of("/users/foo", "/users/foo(id)"),

            // ----------------------------
            // Mixed > Dynamic
            // ----------------------------
            Arguments.of("/items/item-(id)", "/items/(id)"),
            Arguments.of("/items/group/item-(id)", "/items/(id)"),

            // ----------------------------
            // Literal Density inside Mixed
            // ----------------------------
            Arguments.of("/items/item-(id)-detail", "/items/item-(id)"),
            Arguments.of("/items/abc(string)xyz", "/items/a(string)b"),
            Arguments.of("/items/a(string)bc", "/items/a(string)b"),

            // ----------------------------
            // Prefix / Suffix Literal Context
            // ----------------------------
            Arguments.of("/users/prefix(id)", "/users/(id)"),
            Arguments.of("/users/(id)suffix", "/users/(id)"),
            Arguments.of("/users/prefix(id)suffix", "/users/(id)"),

            // ----------------------------
            // Token Count
            // ----------------------------
            Arguments.of("/items/(id)", "/items/(a)/(b)"),
            Arguments.of("/items/item-(id)", "/items/(a)-(b)"),

            // ----------------------------
            // Matcher / Substitution Tokens
            // ----------------------------
            Arguments.of("/users/list", $$"/users/$match()"),
            Arguments.of($$"/users/foo$match()", $$"/users/$match()"),
            Arguments.of("/users/list", "/users/$(a.b.c)"),
            Arguments.of("/users/foo$(a.b.c)", "/users/$(a.b.c)"),

            // ----------------------------
            // Literal Context with Dynamic Tokens
            // ----------------------------
            Arguments.of("/users/(id)extra", "/users/(id)"),
            Arguments.of($$"/users/$match()extra", $$"/users/$match()"),
            Arguments.of("/users/$(a.b.c)extra", "/users/$(a.b.c)"),

            // ----------------------------
            // Static Literal vs Dynamic Tokens
            // ----------------------------
            Arguments.of("/orders/order-123", $$"/orders/$match()"),
            Arguments.of("/orders/order-123", "/orders/$(a.b.c)"),

            // ----------------------------
            // Path Depth
            // ----------------------------
            Arguments.of("/a/b/c/d", "/a/b/c"),
            Arguments.of("/users/list/all", "/users/list")
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
            Arguments.of(mapOf("param1" to "value1", "param2" to "\$eq(A.B.C)"), 1),
        )

        @JvmStatic
        fun headersToExpectedSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("Content-Type" to "application/json"), 1),
            Arguments.of(mapOf("Content-Type" to "(string)"), 0),
            Arguments.of(mapOf("Authorization" to "Bearer token123"), 1),
            Arguments.of(mapOf("Content-Type" to "application/json", "Accept" to "application/json"), 2),
            Arguments.of(mapOf("Content-Type" to "(string)", "Accept" to "application/json"), 1),
            Arguments.of(mapOf("Content-Type" to "application/json", "Accept" to "(string)"), 1),
            Arguments.of(mapOf("Content-Type" to "application/json", "Accept" to "\$eq(A.B.C)"), 1),
            Arguments.of(mapOf("Content-Type" to "(string)", "Accept" to "(string)"), 0)
        )

        @JvmStatic
        fun bodyToExpectedSpecificity(): Stream<Arguments> = Stream.of(
            Arguments.of(parsedJSONObject("""{"id": "10", "count": "10"}"""), 2),
            Arguments.of(parsedJSONObject("""{"id": "(string)", "count": "10"}"""), 1),
            Arguments.of(parsedJSONObject("""{"id": "10", "count": "(string)"}"""), 1),
            Arguments.of(parsedJSONObject("""{"id": "10", "count": "${"$"}eq(A.B.C)"}"""), 1),
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
            Arguments.of(parsedJSONArray("""["10", "${"$"}eq(A.B.C)"]"""), 1),
            Arguments.of(parsedJSONArray("""["(string)", "(string)"]"""), 0),

            Arguments.of(parsedJSONObject("""{"data": {"id": "10", "count": "10"}}"""), 2),
            Arguments.of(parsedJSONObject("""{"data": ["10", "20"]}"""), 2)
        )

        @JvmStatic
        fun xmlContentTypeHeaders(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("Content-Type" to "application/xml")),
            Arguments.of(mapOf("Content-Type" to "text/xml")),
            Arguments.of(mapOf("Content-Type" to "application/soap+xml")),
            Arguments.of(mapOf("Content-Type" to "application/xml; charset=utf-8")),
            Arguments.of(mapOf("SOAPAction" to "someAction")),
        )

        @JvmStatic
        fun nonXmlContentTypeHeaders(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("Content-Type" to "application/json")),
            Arguments.of(emptyMap<String, String>()),
        )

        @JvmStatic
        fun httpRequestSpecificityOrdering(): Stream<Arguments> = Stream.of(
            // ----------------------------
            // Path specificity
            // ----------------------------
            Arguments.of(HttpRequest(path = "/persons/123"), HttpRequest(path = "/persons/(id)")),
            Arguments.of(HttpRequest(path = "/users/list/all"), HttpRequest(path = "/users/(id)")),
            Arguments.of(HttpRequest(path = "/items/item-(id)"), HttpRequest(path = "/items/(id)")),

            // ----------------------------
            // Header specificity
            // ----------------------------
            Arguments.of(HttpRequest(headers = mapOf("Content-Type" to "application/json")), HttpRequest(headers = mapOf("Content-Type" to "(string)"))),
            Arguments.of(HttpRequest(headers = mapOf("Accept" to "application/json")), HttpRequest(headers = mapOf("Accept" to "\$match()"))),

            // ----------------------------
            // Query parameter specificity
            // ----------------------------
            Arguments.of(HttpRequest(queryParametersMap = mapOf("id" to "123")), HttpRequest(queryParametersMap = mapOf("id" to "(number)"))),
            Arguments.of(HttpRequest(queryParametersMap = mapOf("status" to "active")), HttpRequest(queryParametersMap = mapOf("status" to "\$match()"))),

            // ----------------------------
            // Body specificity
            // ----------------------------
            Arguments.of(HttpRequest(body = parsedJSONObject("""{"id":"10"}""")), HttpRequest(body = parsedJSONObject("""{"id":"(string)"}"""))),
            Arguments.of(HttpRequest(body = parsedJSONObject("""{"count":"10"}""")), HttpRequest(body = parsedJSONObject($$"""{"count":"$match()"}"""))),
            // ----------------------------
            // Path dominates other parts
            // ----------------------------
            Arguments.of(HttpRequest(path="/users/123"), HttpRequest(path="/users/(id)", headers=mapOf("X-Test" to "value"))),
            Arguments.of(HttpRequest(path="/orders/list"), HttpRequest(path="/orders/(id)", queryParametersMap=mapOf("status" to "active"))),

            // ----------------------------
            // Literal context
            // ----------------------------
            Arguments.of(HttpRequest(path="/users/prefix(id)"), HttpRequest(path="/users/(id)")),
            Arguments.of(HttpRequest(path="/items/item-(id)-detail"), HttpRequest(path="/items/item-(id)"))
        )
    }

    @Nested
    inner class AdjustRequestForContentTypeTests {
        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestTest#xmlContentTypeHeaders")
        fun `should adjust body for XML content type headers`(headers: Map<String, String>) {
            val xmlBody = StringValue("<data>test</data>")
            val request = HttpRequest(
                method = "POST",
                path = "/test",
                headers = headers,
                body = xmlBody,
            )

            val adjustedRequest = request.adjustPayloadForContentType()
            assertThat(adjustedRequest.body).isInstanceOf(XMLNode::class.java)
            assertThat(adjustedRequest.body.toStringLiteral()).contains("<data>")
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpRequestTest#nonXmlContentTypeHeaders")
        fun `should not adjust body for non-XML content type headers`(headers: Map<String, String>) {
            val body = StringValue("<data>test</data>")
            val request = HttpRequest(
                method = "POST",
                path = "/test",
                headers = headers,
                body = body,
            )

            val adjustedRequest = request.adjustPayloadForContentType()

            assertThat(adjustedRequest.body).isEqualTo(body)
            assertThat(adjustedRequest.body.toStringLiteral()).isEqualTo("<data>test</data>")
        }

        @Test
        fun `should not adjust non-XML value types`() {
            val jsonBody = parsedJSONObject("""{"key": "value"}""")
            val request = HttpRequest(
                method = "POST",
                path = "/test",
                headers = mapOf("Content-Type" to "application/xml"),
                body = jsonBody
            )

            val adjustedRequest = request.adjustPayloadForContentType()

            assertThat(adjustedRequest.body).isEqualTo(jsonBody)
        }

        @Test
        fun `should parse stringified XML in body to XMLNode if content-type indicates xml`() {
            val exampleFile = File("src/test/resources/openapi/has_xml_payloads/api_examples/createInventory.json")
            val example = readValueAs<JSONObjectValue>(exampleFile)
            val response = requestFromJSON(example.getJSONObject(MOCK_HTTP_REQUEST))
            assertThat(response.body).isInstanceOf(XMLNode::class.java)
        }

        @Test
        fun `should not parse stringified XML in body to XMLNode if content-type does not indicates xml`() {
            val exampleFile = File("src/test/resources/openapi/has_xml_payloads/api_examples/createInventory.json")
            val example = readValueAs<JSONObjectValue>(exampleFile)
            val rawRequestJson = ObjectValueOperator(example.getJSONObject(MOCK_HTTP_REQUEST)).let {
                it.update("headers/Content-Type", StringValue("plain/text"))
                    .unwrapOrContractException().finalize().value as JSONObjectValue
            }

            val request = requestFromJSON(rawRequestJson.jsonObject)
            assertThat(request.body).isInstanceOf(StringValue::class.java)
        }
    }

    // SOAPAction header exists
    // Content-Type = application/soap+xml; action=SOAPActionName -- double check
    // Any other content type
    // no content type

    @Nested
    inner class ProtocolDetectionTests {
        @Test
        fun `should return the SOAP protocol for SOAP 1_1`() {
            val httpRequest =
                HttpRequest(
                    "POST",
                    "/soap-service",
                    headers = mapOf("Content-Type" to "text/xml", "SOAPAction" to "SomeAction"),
                )
            assertThat(httpRequest.protocol).isEqualTo(SpecmaticProtocol.SOAP)
        }

        @Test
        fun `should return the SOAP protocol for for SOAP 1_2`() {
            val httpRequest =
                HttpRequest("POST", "/soap-service", headers = mapOf("Content-Type" to "application/soap+xml"))
            assertThat(httpRequest.protocol).isEqualTo(SpecmaticProtocol.SOAP)

            val httpRequest2 =
                HttpRequest("POST", "/soap-service", headers = mapOf("Content-Type" to "application/soap+xml; action=SomeSOAPAction"))
            assertThat(httpRequest2.protocol).isEqualTo(SpecmaticProtocol.SOAP)
        }

        @Test
        fun `should return the HTTP protocol for any non-soap content-type`() {
            val httpRequest =
                HttpRequest("POST", "/rest-service", headers = mapOf("Content-Type" to "application/json"))
            assertThat(httpRequest.protocol).isEqualTo(SpecmaticProtocol.HTTP)
        }

        @Test
        fun `should return the HTTP protocol for a missing content-type`() {
            val httpRequest = HttpRequest("POST", "/rest-service", headers = emptyMap())
            assertThat(httpRequest.protocol).isEqualTo(SpecmaticProtocol.HTTP)
        }
    }
}
