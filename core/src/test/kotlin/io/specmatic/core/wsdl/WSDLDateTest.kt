package io.specmatic.core.wsdl

import io.specmatic.core.*
import io.specmatic.core.value.StringValue
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.XsdDatePattern
import io.specmatic.core.pattern.ScalarType
import io.specmatic.core.pattern.Examples
import io.specmatic.core.pattern.Row
import io.specmatic.mock.ScenarioStub
import io.specmatic.reporter.commands.InsightsReportOptions
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class WSDLDateTest {
    @TempDir lateinit var directory: File

    @Test
    fun `plain XSD date rejects arbitrary text in mock requests`() {
        val feature = load()
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request("not-a-date"))
            assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(400)
            assertThat(response.body.toStringLiteral()).contains("Request", "not-a-date")
        }
    }

    @Test
    fun `derived date with a supported pattern restriction still enforces primitive validity`() {
        val definitions = """<xs:simpleType name="DateBase"><xs:restriction base="xs:date"><xs:pattern value="[0-9-]+"/></xs:restriction></xs:simpleType>
            <xs:simpleType name="DateAlias"><xs:restriction base="tns:DateBase"/></xs:simpleType>"""
        val feature = load("tns:DateAlias", definitions = definitions)
        HttpStub(feature, port = testPort()).use { stub ->
            assertThat(stub.client.execute(request("2024-02-29")).status).isEqualTo(200)
            assertThat(stub.client.execute(request("2025-02-29")).status).isEqualTo(400)
            assertThat(stub.client.execute(request("2024-02-29Z")).status).isEqualTo(400)
        }
    }

    @Test
    fun `mock date lexical and calendar rules agree with independent XSD validation`() {
        val valid = listOf("2024-02-29", "2000-02-29", "2025-01-31", "2024-02-29Z", "2024-02-29+05:30", "2024-02-29-07:00", "2024-02-29+14:00", "2024-02-29-14:00", "-0001-01-01", "12345-01-01", "-12345-01-01", " \t2024-02-29Z\n ")
        val invalid = listOf("not-a-date", "2025-02-29", "1900-02-29", "2024-13-01", "2024-00-01", "2024-04-31", "2024-01-00", "0000-01-01", "-0000-01-01", "01234-01-01", "-01234-01-01", "+2024-01-01", "024-01-01", "2024-1-01", "2024-01-1", "2024-01-01z", "2024-01-01+00:60", "2024-01-01+14:01", "2024-01-01-15:00", "2024-01-01+1:00", "2024-01-01+0100", "2024-01-01T00:00:00Z", "2024-01", "2024", "2024-01-01 Z", "\u00a02024-01-01", "2024-01-01\u2003")
        HttpStub(load(), port = testPort()).use { stub ->
            (valid.map { it to true } + invalid.map { it to false }).forEach { (value, expected) ->
                assertThat(schemaAccepts(soap("Request", value))).describedAs("XSD oracle: '$value'").isEqualTo(expected)
                val response = stub.client.execute(request(value))
                assertThat(response.status).withFailMessage("Date '$value': ${response.body.toStringLiteral()}").isEqualTo(if (expected) 200 else 400)
            }
        }
    }

    @Test
    fun `fixed provider response dates are validated at the response field`() {
        val feature = load()
        listOf("2024-02-29Z" to true, "not-a-date" to false, "2025-02-29" to false).forEach { (value, valid) ->
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(schemaAccepts(request.body.toStringLiteral())).isTrue()
                    return response(value)
                }
            })
            assertThat(results.success()).withFailMessage(results.report()).isEqualTo(valid)
            if (valid) assertThat(results.successCount).isGreaterThan(0)
            else assertThat(results.report()).contains("RESPONSE", "Response", value)
        }
    }

    @Test
    fun `response widening from date to unrestricted string breaks even when generated strings look like dates`() {
        val oldFeature = load(requestType = "xs:string", responseType = "xs:date")
        val newFeature = load(requestType = "xs:string", responseType = "xs:string")
        val provider = object : StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>) = "2024-02-29"
        }
        StringProviders.with(provider) {
            val result = testBackwardCompatibility(oldFeature, newFeature, InsightsReportOptions())
            assertThat(result.success()).withFailMessage(result.report()).isFalse()
            assertThat(result.report()).contains("RESPONSE", "Response", "XSD date", "string")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["xs:date", "xs:string"])
    fun `authored scalar example retains XML whitespace and timezone spelling during contract testing`(type: String) {
        val value = if (type == "xs:date") " \t2024-02-29+00:00\n " else "first line\n second line\n  third line"
        load(requestType = type, responseType = "xs:date")
        val examples = directory.resolve("examples").apply { mkdirs() }
        examples.resolve("date.json").writeText(ScenarioStub(request = request(value), response = response("2024-02-29Z")).toJSON().toStringLiteral())
        val feature = parseContractFileToFeature(directory.resolve("dates.wsdl"), exampleDirPaths = listOf(examples.path)).loadExternalisedExamples()
        val sent = mutableListOf<String>()
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                sent += soapPayloadElement(request.body.toStringLiteral()).textContent
                return response("2024-02-29Z")
            }
        })
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(sent).containsExactly(value)
    }

    @Test
    fun `invalid positive date example fails at its request field before provider invocation`() {
        load()
        val examples = directory.resolve("examples").apply { mkdirs() }
        examples.resolve("invalid.json").writeText(ScenarioStub(request = request("not-a-date"), response = response("2024-02-29Z")).toJSON().toStringLiteral())
        val feature = parseContractFileToFeature(directory.resolve("dates.wsdl"), exampleDirPaths = listOf(examples.path)).loadExternalisedExamples()
        val sent = mutableListOf<HttpRequest>()
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                sent += request
                return response("2024-02-29Z")
            }
        })
        assertThat(results.success()).withFailMessage(results.report()).isFalse()
        assertThat(results.report()).contains("REQUEST", "Request", "not-a-date").doesNotContain("RESPONSE.STATUS")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `explicit negative 4xx example sends its deliberately invalid date to the provider`() {
        val feature = load().let { original -> original.copy(scenarios = original.scenarios.map { scenario ->
            scenario.copy(httpResponsePattern = scenario.httpResponsePattern.copy(status = 400), examples = listOf(
                Examples(rows = listOf(Row(name = "invalid date", requestExample = request("not-a-date"), responseExample = response("2024-02-29Z").copy(status = 400))))
            ))
        }) }
        feature.validateExamplesOrException()
        val sent = mutableListOf<String>()
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                sent += soapPayloadElement(request.body.toStringLiteral()).textContent
                return response("2024-02-29Z").copy(status = 400)
            }
        })
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(sent).containsExactly("not-a-date")
    }

    @ParameterizedTest
    @ValueSource(strings = ["named", "reference", "attribute", "simpleContent", "list", "union"])
    fun `supported WSDL placements retain date semantics in mock and contract modes`(placement: String) {
        val definitions = """<xs:simpleType name="DateAlias"><xs:restriction base="xs:date"/></xs:simpleType>
            <xs:simpleType name="Dates"><xs:list itemType="tns:DateAlias"/></xs:simpleType>
            <xs:simpleType name="DateOrTime"><xs:union memberTypes="tns:DateAlias xs:dateTime"/></xs:simpleType>
            <xs:element name="DateValue" type="tns:DateAlias"/>"""
        fun declaration(name: String) = when (placement) {
            "reference" -> """<xs:element name="$name"><xs:complexType><xs:sequence><xs:element ref="tns:DateValue"/></xs:sequence></xs:complexType></xs:element>"""
            "attribute" -> """<xs:element name="$name"><xs:complexType><xs:attribute name="when" type="tns:DateAlias" use="required"/></xs:complexType></xs:element>"""
            "simpleContent" -> """<xs:element name="$name"><xs:complexType><xs:simpleContent><xs:extension base="tns:DateAlias"/></xs:simpleContent></xs:complexType></xs:element>"""
            else -> """<xs:element name="$name" type="tns:${when (placement) { "list" -> "Dates"; "union" -> "DateOrTime"; else -> "DateAlias" }}"/>"""
        }
        val document = schema("xs:date", "xs:date", definitions).let { xml ->
            listOf("Request", "Response").fold(xml) { source, name -> source.replace("""<xs:element name="$name" type="xs:date"/>""", declaration(name)) }
        }
        fun payload(name: String, value: String) = when (placement) {
            "reference" -> soap(name, "<t:DateValue>$value</t:DateValue>")
            "attribute" -> soap(name, "").replace("<t:$name>", """<t:$name when="$value">""")
            else -> soap(name, value)
        }
        val feature = parseContractFileToFeature(directory.resolve("placement.wsdl").apply { writeText(wsdl(document)) })
        val valid = if (placement == "list") listOf("2024-02-29 2000-02-29Z") else if (placement == "union") listOf("2024-02-29Z", "2024-02-29T12:00:00Z") else listOf("2024-02-29Z")
        val invalid = if (placement == "list") "2024-02-29 not-a-date" else "not-a-date"
        HttpStub(feature, port = testPort()).use { stub ->
            (valid.map { it to true } + (invalid to false)).forEach { (value, accepted) ->
                val body = payload("Request", value)
                assertThat(schemaAccepts(body, document)).isEqualTo(accepted)
                val response = stub.client.execute(request(value).copy(body = StringValue(body)))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(if (accepted) 200 else 400)
                if (accepted) assertThat(schemaAccepts(response.body.toStringLiteral(), document)).isTrue()
                else assertThat(response.body.toStringLiteral()).contains("Request", "not-a-date")
            }
        }
        (valid.map { it to true } + (invalid to false)).forEach { (value, accepted) ->
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest): HttpResponse {
                    assertThat(schemaAccepts(request.body.toStringLiteral(), document)).isTrue()
                    return response(value).copy(body = StringValue(payload("Response", value)))
                }
            })
            assertThat(results.success()).withFailMessage(results.report()).isEqualTo(accepted)
            if (accepted) assertThat(results.successCount).isGreaterThan(0)
            else assertThat(results.report()).contains("RESPONSE", "Response", "not-a-date")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\\c+", "[0-9 -]+"])
    fun `unsupported extra date facets remain nonblocking while primitive validity applies`(regex: String) {
        val definitions = """<xs:simpleType name="LimitedDate"><xs:restriction base="xs:date">
            <xs:minInclusive value="2099-01-01"/><xs:pattern value="$regex"/>
        </xs:restriction></xs:simpleType>"""
        val feature = load("tns:LimitedDate", definitions = definitions)
        HttpStub(feature, port = testPort()).use { stub ->
            assertThat(stub.client.execute(request("2024-02-29")).status).isEqualTo(200)
            assertThat(stub.client.execute(request("not-a-date")).status).isEqualTo(400)
        }
    }

    @Test
    fun `request compatibility keeps generated request behavior for string and date contracts`() {
        val stringRequest = load(requestType = "xs:string", responseType = "xs:string")
        val dateRequest = load(requestType = "xs:date", responseType = "xs:string")
        assertThat(testBackwardCompatibility(dateRequest, stringRequest, InsightsReportOptions()).success()).isTrue()
        assertThat(testBackwardCompatibility(dateRequest, dateRequest, InsightsReportOptions()).success()).isTrue()
        assertThat(testBackwardCompatibility(stringRequest, dateRequest, InsightsReportOptions()).success()).isFalse()
        StringProviders.with(object : StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>) = "2024-02-29"
        }) {
            assertThat(testBackwardCompatibility(stringRequest, dateRequest, InsightsReportOptions()).success()).isTrue()
        }
    }

    @Test
    fun `date containment depends on explicit comparison context rather than diagnostic messages`() {
        StringProviders.with(object : StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>) = "2024-02-29"
        }) {
            val date = XsdDatePattern()
            val string = StringPattern()
            listOf(DefaultMismatchMessages, NewAndOldSpecificationResponseMismatches,
                object : MismatchMessages by NewAndOldSpecificationResponseMismatches {}).forEach { messages ->
                val responseResolver = Resolver(mismatchMessages = messages, isResponseCompatibilityCheck = true)
                assertThat(date.encompasses(string, responseResolver, responseResolver).isSuccess()).isFalse()
                assertThat(string.encompasses(date, responseResolver, responseResolver).isSuccess()).isTrue()
                val ordinaryResolver = Resolver(mismatchMessages = messages)
                assertThat(date.encompasses(string, ordinaryResolver, ordinaryResolver).isSuccess()).isTrue()
            }
        }
    }

    @Test
    fun `response narrowing to date remains compatible and constrained strings keep existing comparison`() {
        val stringResponse = load(requestType = "xs:string", responseType = "xs:string")
        val dateResponse = load(requestType = "xs:string", responseType = "xs:date")
        assertThat(testBackwardCompatibility(stringResponse, dateResponse, InsightsReportOptions()).success()).isTrue()
        assertThat(testBackwardCompatibility(dateResponse, dateResponse, InsightsReportOptions()).success()).isTrue()
        val definitions = """<xs:simpleType name="DateText"><xs:restriction base="xs:string"><xs:pattern value="2024-02-29"/></xs:restriction></xs:simpleType>
            <xs:simpleType name="ShortText"><xs:restriction base="xs:string"><xs:maxLength value="2"/></xs:restriction></xs:simpleType>"""
        val dateText = load(requestType = "xs:string", responseType = "tns:DateText", definitions = definitions)
        val shortText = load(requestType = "xs:string", responseType = "tns:ShortText", definitions = definitions)
        assertThat(testBackwardCompatibility(dateResponse, dateText, InsightsReportOptions()).success()).isTrue()
        val incompatible = testBackwardCompatibility(shortText, dateResponse, InsightsReportOptions())
        assertThat(incompatible.success()).isFalse()
        assertThat(incompatible.report()).contains("RESPONSE", "Response")
    }

    @Test
    fun `response-specific date compatibility rule does not affect mock pattern tokens`() {
        StringProviders.with(object : StringProvider {
            override fun getFor(pattern: ScalarType, resolver: Resolver, path: List<String>) = "2024-02-29"
        }) {
            val example = ScenarioStub(request = request("(string)"), response = response("2000-02-29Z"))
            HttpStub(load(), scenarioStubs = listOf(example), port = testPort()).use { stub ->
                val response = stub.client.execute(request("2024-02-29"))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
                assertThat(soapPayloadElement(response.body.toStringLiteral()).textContent).isEqualTo("2000-02-29Z")
            }
        }
    }

    @Test
    fun `generated dates satisfy a supported fixed lexical restriction`() {
        val definitions = """<xs:simpleType name="LeapDay"><xs:restriction base="xs:date"><xs:pattern value="2024-02-29"/></xs:restriction></xs:simpleType>"""
        val feature = load("tns:LeapDay", definitions = definitions)
        HttpStub(feature, port = testPort()).use { stub ->
            val generated = stub.client.execute(request("2024-02-29"))
            assertThat(generated.status).withFailMessage(generated.body.toStringLiteral()).isEqualTo(200)
            assertThat(schemaAccepts(generated.body.toStringLiteral(), schema("tns:LeapDay", "tns:LeapDay", definitions))).isTrue()
        }
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                assertThat(schemaAccepts(request.body.toStringLiteral(), schema("tns:LeapDay", "tns:LeapDay", definitions))).isTrue()
                return response("2024-02-29")
            }
        })
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.successCount).isGreaterThan(0)
    }

    private fun response(value: String) = HttpResponse(200, headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", value)))

    private fun schemaAccepts(xml: String, document: String = schema("xs:date", "xs:date", "")): Boolean {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val validator = factory.newSchema(StreamSource(StringReader(document))).newValidator()
        return runCatching { validator.validate(DOMSource(soapPayloadElement(xml))) }.isSuccess
    }

    private fun load(requestType: String = "xs:date", responseType: String = requestType, definitions: String = "") =
        parseContractFileToFeature(directory.resolve("dates.wsdl").apply { writeText(wsdl(schema(requestType, responseType, definitions))) })

    private fun schema(requestType: String, responseType: String, definitions: String) = """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$NAMESPACE" targetNamespace="$NAMESPACE" elementFormDefault="qualified">
        $definitions
        <xs:element name="Request" type="$requestType"/>
        <xs:element name="Response" type="$responseType"/>
    </xs:schema>"""

    private fun request(value: String) = HttpRequest("POST", "/inline", headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "/inline/submit"), body = StringValue(soap("Request", value)))
    private fun soap(name: String, value: String) = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="$NAMESPACE"><s:Body><t:$name>$value</t:$name></s:Body></s:Envelope>"""

    companion object {
        private const val NAMESPACE = "urn:wsdl-dates"
        private fun wsdl(schema: String): String = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:tns="$NAMESPACE" targetNamespace="$NAMESPACE">
                <wsdl:types>$schema</wsdl:types>
                <wsdl:message name="Input"><wsdl:part name="body" element="tns:Request"/></wsdl:message>
                <wsdl:message name="Output"><wsdl:part name="body" element="tns:Response"/></wsdl:message>
                <wsdl:portType name="Port"><wsdl:operation name="submit">
                    <wsdl:input message="tns:Input"/><wsdl:output message="tns:Output"/>
                </wsdl:operation></wsdl:portType>
                <wsdl:binding name="Binding" type="tns:Port">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="submit"><soap:operation soapAction="/inline/submit"/>
                        <wsdl:input><soap:body use="literal"/></wsdl:input>
                        <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="InlineTypes"><wsdl:port name="Port" binding="tns:Binding">
                    <soap:address location="http://localhost:9000/inline"/>
                </wsdl:port></wsdl:service>
            </wsdl:definitions>
        """.trimIndent()
    }
}
