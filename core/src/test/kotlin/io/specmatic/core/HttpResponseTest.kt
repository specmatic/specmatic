package io.specmatic.core

import io.specmatic.core.GherkinSection.Then
import io.specmatic.core.jsonoperator.value.ObjectValueOperator
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.parsedJSON
import io.specmatic.core.pattern.parsedJSONObject
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.pattern.readValueAs
import io.specmatic.core.pattern.unwrapOrContractException
import io.specmatic.core.utilities.Flags
import io.specmatic.core.value.*
import io.specmatic.mock.MOCK_HTTP_RESPONSE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

internal class HttpResponseTest {
    @Test
    fun createANewResponseObjectWithInitialValues() {
        val response = HttpResponse(500, "ERROR", HashMap())
        assertEquals(500, response.status)
        assertEquals(StringValue("ERROR"), response.body)
    }

    @Test
    fun createANewResponseObjectWithoutInitialValues() {
        val response = HttpResponse.EMPTY
        assertEquals(0, response.status)
        assertEquals(EmptyString, response.body)
    }

    @Test
    fun `updating body with value should automatically set Content-Type header`() {
        HttpResponse.EMPTY.updateBodyWith(parsedValue("""{"name": "John Doe"}""")).let {
            val responseBody = it.body

            if(responseBody !is JSONObjectValue)
                throw AssertionError("Expected responseBody to be a JSON object, but got ${responseBody.javaClass.name}")

            assertEquals("John Doe", responseBody.jsonObject.getValue("name").toStringLiteral())
            assertEquals("application/json", it.headers.getOrDefault("Content-Type", ""))
        }
    }

    @Test
    fun `gherkin clauses from simple 200 response`() {
        val clauses = toGherkinClauses(HttpResponse.OK)

        assertThat(clauses.first).hasSize(1)
        assertThat(clauses.first.single().section).isEqualTo(Then)
        assertThat(clauses.first.single().content).isEqualTo("status 200")
    }

    @Test
    fun `gherkin clauses from response with headers`() {
        val clauses = toGherkinClauses(HttpResponse(200, headers = mapOf("X-Value" to "10"), body = EmptyString))

        assertThat(clauses.first).hasSize(2)
        assertThat(clauses.first.first().section).isEqualTo(Then)
        assertThat(clauses.first.first().content).isEqualTo("status 200")

        assertThat(clauses.first[1].section).isEqualTo(Then)
        assertThat(clauses.first[1].content).isEqualTo("response-header X-Value (integer)")
    }

    @Test
    fun `gherkin clauses from response with body`() {
        val clauses = toGherkinClauses(HttpResponse(200, headers = emptyMap(), body = StringValue("response data")))

        assertThat(clauses.first).hasSize(2)
        assertThat(clauses.first.first().section).isEqualTo(Then)
        assertThat(clauses.first.first().content).isEqualTo("status 200")

        assertThat(clauses.first[1].section).isEqualTo(Then)
        assertThat(clauses.first[1].content).isEqualTo("response-body (string)")
    }

    @Test
    fun `gherkin clauses from response with number body`() {
        val clauses = toGherkinClauses(HttpResponse(200, headers = emptyMap(), body = StringValue("10")))

        assertThat(clauses.first).hasSize(2)
        assertThat(clauses.first.first().section).isEqualTo(Then)
        assertThat(clauses.first.first().content).isEqualTo("status 200")

        assertThat(clauses.first[1].section).isEqualTo(Then)
        assertThat(clauses.first[1].content).isEqualTo("response-body (integer)")
    }

    @Test
    fun `gherkin clauses should contain no underscores when there are duplicate keys`() {
        val (clauses, _, examples) = toGherkinClauses(HttpResponse(200, body = parsedJSON("""[{"data": 1}, {"data": 2}]""")))

        assertThat(examples).isInstanceOf(DiscardExampleDeclarations::class.java)

        for(clause in clauses) {
            assertThat(clause.content).doesNotContain("_")
        }
    }

    @Test
    fun `response-body selector with no path should return response body`() {
        val response = HttpResponse.ok("hello")
        testSelection(response, "response-body", "hello")
    }

    private fun testSelection(response: HttpResponse, selector: String, expectedValue: String) {
        val selectedValue = response.selectValue(selector)
        assertThat(selectedValue).isEqualTo(expectedValue)
    }

    @Test
    fun `response-body selector with a path should return the JSON value at that path`() {
        val response = HttpResponse.ok(JSONObjectValue(mapOf("token" to NumberValue(10))))
        testSelection(response, "response-body.token", "10")
    }

    @Test
    fun `response-body selector with a path that points to a JSON object should return it`() {
        val nameData = mapOf("name" to StringValue("Jack"))
        val responseBody = JSONObjectValue(mapOf("person" to JSONObjectValue(nameData)))

        val response = HttpResponse.ok(responseBody)
        val selectedValue = response.selectValue("response-body.person")
        val parsedValue = parsedValue(selectedValue)

        assertThat(parsedValue).isEqualTo(JSONObjectValue(nameData))
    }

    @Test
    fun `response-header selector with a path should return the JSON value at that path`() {
        val response = HttpResponse.OK.copy(headers = mapOf("Token" to "abc123"))
        testSelection(response, "response-header.Token", "abc123")
    }

    @Test
    fun `exports bindings`() {
        val response = HttpResponse.ok(JSONObjectValue(mapOf("token" to NumberValue(10))))
        val bindings = response.export(mapOf("token" to "response-body.token"))
        assertThat(bindings).isEqualTo(mapOf("token" to "10"))
    }

    @Test
    fun `throws error if export is not found`() {
        val response = HttpResponse.ok(JSONObjectValue(mapOf("token" to NumberValue(10))))
        assertThatThrownBy { response.export(mapOf("token" to "response-body.notfound")) }.isInstanceOf(ContractException::class.java)
    }

    @Test
    fun `should exclude dynamic headers`() {
        HttpResponse.OK.copy(headers = mapOf("Content-Length" to "10").withoutTransportHeaders()).let {
            assertThat(it.headers).isEmpty()
        }
    }

    @Nested
    inner class IsNotEmptyTests {

        @Test
        fun `should return false both body and headers are empty`() {
            val httpResponse = HttpResponse(
                body = NoBodyValue,
                headers = emptyMap()
            )

            assertThat(httpResponse.isNotEmpty()).isEqualTo(false)
        }

        @Test
        fun `should return true both body and headers are not empty`() {
            val httpResponse = HttpResponse(
                body = "body",
                headers = mapOf("X-traceId" to "traceId")
            )

            assertThat(httpResponse.isNotEmpty()).isEqualTo(true)
        }

        @Test
        fun `should return true if body is empty but headers are not empty`() {
            val httpResponse = HttpResponse(
                headers = mapOf("X-traceId" to "traceId")
            )

            assertThat(httpResponse.isNotEmpty()).isEqualTo(true)
        }

        @Test
        fun `should return true if headers are empty but body is not empty`() {
            val httpResponse = HttpResponse(
                body = "body",
            )

            assertThat(httpResponse.isNotEmpty()).isEqualTo(true)
        }
    }

    @Test
    fun `should pretty-print response body by default`() {
        val request = HttpResponse(200, body = parsedJSONObject("""{"id": 10}"""))
        assertThat(request.toLogString())
            .contains(""" "id": 10""")
            .doesNotContain("""{"id":10}""")
    }

    @Test
    fun `should print response body in one line when flag is set to false`() {
        try {
            System.setProperty(Flags.SPECMATIC_PRETTY_PRINT, "false")
            val request = HttpResponse(200, body = parsedJSONObject("""{"id": 10}"""))
            assertThat(request.toLogString())
                .contains("""{"id":10}""")
        } finally {
            System.clearProperty(Flags.SPECMATIC_PRETTY_PRINT)
        }
    }

    @Test
    fun `should set the response body as NoBodyValue if the json object does not have the body key`() {
        val response = HttpResponse.fromJSON(
            mapOf("status" to NumberValue(203))
        )

        assertThat(response.body).isEqualTo(NoBodyValue)
        assertThat(response.status).isEqualTo(203)
        assertThat(response.headers).isEmpty()
    }

    @Nested
    inner class AdjustRequestForContentTypeTests {

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpResponseTest#xmlContentTypeScenarios")
        fun `should adjust body when response headers indicate XML`(
            responseHeaders: Map<String, String>,
            requestHeaders: Map<String, String>
        ) {
            val xmlBody = StringValue("<response>data</response>")
            val response = HttpResponse(
                status = 200,
                headers = responseHeaders,
                body = xmlBody
            )

            val adjustedResponse = response.adjustPayloadForContentType(requestHeaders)

            assertThat(adjustedResponse.body).isInstanceOf(XMLNode::class.java)
            assertThat(adjustedResponse.body.toStringLiteral()).contains("<response>")
        }

        @ParameterizedTest
        @MethodSource("io.specmatic.core.HttpResponseTest#nonXmlContentTypeScenarios")
        fun `should not adjust body when neither request nor response headers indicate XML`(
            responseHeaders: Map<String, String>,
            requestHeaders: Map<String, String>
        ) {
            val body = StringValue("<response>data</response>")
            val response = HttpResponse(
                status = 200,
                headers = responseHeaders,
                body = body
            )

            val adjustedResponse = response.adjustPayloadForContentType(requestHeaders)

            assertThat(adjustedResponse.body).isEqualTo(body)
            assertThat(adjustedResponse.body.toStringLiteral()).isEqualTo("<response>data</response>")
        }

        @Test
        fun `should adjust XMLNode body in response`() {
            val xmlNode = XMLNode(
                name = "response",
                realName = "response",
                attributes = emptyMap(),
                childNodes = listOf(StringValue("<data>test</data>")),
                namespacePrefix = "",
                namespaces = emptyMap()
            )
            val response = HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "text/xml"),
                body = xmlNode
            )

            val adjustedResponse = response.adjustPayloadForContentType()

            assertThat(adjustedResponse.body).isInstanceOf(XMLNode::class.java)
            val adjustedXmlNode = adjustedResponse.body as XMLNode
            assertThat(adjustedXmlNode.childNodes).hasSize(1)
            assertThat(adjustedXmlNode.childNodes[0]).isInstanceOf(StringValue::class.java)
            assertThat((adjustedXmlNode.childNodes[0] as StringValue).toStringLiteral()).contains("&lt;data&gt;")
        }

        @Test
        fun `should adjust based on request headers when response has no Content-Type`() {
            val xmlBody = StringValue("<data>test</data>")
            val response = HttpResponse(
                status = 200,
                headers = emptyMap(),
                body = xmlBody
            )
            val requestHeaders = mapOf("Content-Type" to "application/xml")

            val adjustedResponse = response.adjustPayloadForContentType(requestHeaders)

            assertThat(adjustedResponse.body).isInstanceOf(XMLNode::class.java)
            assertThat(adjustedResponse.body.toStringLiteral()).contains("<data>")
        }

        @Test
        fun `should adjust based on SOAPAction in request headers`() {
            val xmlBody = StringValue("<soap:Envelope>content</soap:Envelope>")
            val response = HttpResponse(
                status = 200,
                headers = emptyMap(),
                body = xmlBody
            )
            val requestHeaders = mapOf("SOAPAction" to "urn:test")

            val adjustedResponse = response.adjustPayloadForContentType(requestHeaders)

            assertThat(adjustedResponse.body).isInstanceOf(XMLNode::class.java)
            assertThat(adjustedResponse.body.toStringLiteral()).contains("<soap:Envelope>")
        }

        @Test
        fun `should not adjust non-XML value types in response`() {
            val jsonBody = parsedJSONObject("""{"result": "success"}""")
            val response = HttpResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/xml"),
                body = jsonBody,
            )

            val adjustedResponse = response.adjustPayloadForContentType()

            assertThat(adjustedResponse.body).isEqualTo(jsonBody)
        }

        @Test
        fun `should parse stringified XML in body to XMLNode if content-type indicates xml`() {
            val exampleFile = File("src/test/resources/openapi/has_xml_payloads/api_examples/createInventory.json")
            val example = readValueAs<JSONObjectValue>(exampleFile)
            val response = HttpResponse.fromJSON(example.getJSONObject(MOCK_HTTP_RESPONSE))
            assertThat(response.body).isInstanceOf(XMLNode::class.java)
        }

        @Test
        fun `should not parse stringified XML in body to XMLNode if content-type does not indicates xml`() {
            val exampleFile = File("src/test/resources/openapi/has_xml_payloads/api_examples/createInventory.json")
            val example = readValueAs<JSONObjectValue>(exampleFile)
            val rawResponseJson = ObjectValueOperator(example.getJSONObject(MOCK_HTTP_RESPONSE)).let {
                it.update("headers/Content-Type", StringValue("plain/text"))
                    .unwrapOrContractException().finalize().value as JSONObjectValue
            }

            val response = HttpResponse.fromJSON(rawResponseJson.jsonObject)
            assertThat(response.body).isInstanceOf(StringValue::class.java)
        }
    }

    @Test
    fun `when replacing old text with new response body type should be preserved`() {
        val originalResponse = HttpResponse(
            status = 200,
            headers = mapOf("Content-Type" to "application/json"),
            body = parsedJSONObject("""{"data": "original"}"""),
        )

        val updatedResponse = originalResponse.replaceString("original", "updated")

        assertThat(updatedResponse.body).isInstanceOf(JSONObjectValue::class.java)
        val bodyAsObject = updatedResponse.body as JSONObjectValue
        assertThat(bodyAsObject.jsonObject["data"]).isInstanceOf(StringValue::class.java)
        assertThat((bodyAsObject.jsonObject["data"] as StringValue).toStringLiteral()).isEqualTo("updated")
    }

    companion object {
        @JvmStatic
        fun xmlContentTypeScenarios(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("Content-Type" to "application/xml"), emptyMap<String, String>()),
            Arguments.of(mapOf("Content-Type" to "text/xml"), emptyMap<String, String>()),
            Arguments.of(mapOf("Content-Type" to "application/soap+xml"), emptyMap<String, String>()),
            Arguments.of(emptyMap<String, String>(), mapOf("Content-Type" to "application/xml")),
            Arguments.of(emptyMap<String, String>(), mapOf("SOAPAction" to "test")),
            Arguments.of(mapOf("Content-Type" to "text/xml"), mapOf("SOAPAction" to "test")),
        )

        @JvmStatic
        fun nonXmlContentTypeScenarios(): Stream<Arguments> = Stream.of(
            Arguments.of(mapOf("Content-Type" to "application/json"), emptyMap<String, String>()),
            Arguments.of(emptyMap<String, String>(), mapOf("Content-Type" to "application/json")),
            Arguments.of(emptyMap<String, String>(), emptyMap<String, String>())
        )
    }
}
