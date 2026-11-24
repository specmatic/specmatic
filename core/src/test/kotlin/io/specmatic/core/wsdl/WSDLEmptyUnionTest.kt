package io.specmatic.core.wsdl

import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpResponse
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.xml.sax.InputSource
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WSDLEmptyUnionTest {
    @TempDir lateinit var directory: File

    @Test
    fun `present empty list union works through mock and provider response validation`() {
        val definition = """<xs:union memberTypes="xs:boolean tns:Codes"/>"""
        val extra = """<xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>"""
        val feature = load(definition, extraDefinitions = extra)
        HttpStub(feature, port = testPort()).use { stub ->
            val response = stub.client.execute(request(""))
            assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
        }
        val results = feature.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest) = HttpResponse(200,
                headers = mapOf(CONTENT_TYPE to "text/xml"), body = StringValue(soap("Response", "")))
        })
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(results.successCount).isGreaterThan(0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["xs:boolean tns:Codes", "tns:Codes xs:boolean", "tns:Inner xs:boolean"])
    fun `empty lexical spellings and alternatives preserve union composition`(members: String) {
        val definition = """<xs:union memberTypes="$members"/>"""
        val extra = """<xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>
            <xs:simpleType name="Inner"><xs:union memberTypes="xs:boolean tns:Codes"/></xs:simpleType>"""
        val feature = load(definition, extraDefinitions = extra)
        HttpStub(feature, port = testPort()).use { stub ->
            listOf("", " \t\n", "true", "BA AA").forEach { value ->
                val payload = soap("Request", value)
                assertSchemaValidPayload(payload, definition, false, extra)
                val response = stub.client.execute(request(value))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(200)
                val results = feature.executeTests(object : TestExecutor {
                    override fun execute(request: HttpRequest) = HttpResponse(200, mapOf(CONTENT_TYPE to "text/xml"), StringValue(soap("Response", value)))
                })
                assertThat(results.success()).withFailMessage(results.report()).isTrue()
            }
            val selfClosing = soap("Request", "").replace("<t:Request></t:Request>", "<t:Request/>")
            assertSchemaValidPayload(selfClosing, definition, false, extra)
            assertThat(stub.client.execute(request("").copy(body = StringValue(selfClosing))).status).isEqualTo(200)
            val selfClosingResponse = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200, mapOf(CONTENT_TYPE to "text/xml"),
                    StringValue(soap("Response", "").replace("<t:Response></t:Response>", "<t:Response/>")))
            })
            assertThat(selfClosingResponse.success()).withFailMessage(selfClosingResponse.report()).isTrue()
            val invalid = stub.client.execute(request("BA a1"))
            assertThat(invalid.status).isEqualTo(400)
            assertThat(invalid.body.toStringLiteral()).contains("Request", "a1", "[1]", "XSD union")
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200, mapOf(CONTENT_TYPE to "text/xml"), StringValue(soap("Response", "BA a1")))
            })
            assertThat(results.success()).isFalse()
            assertThat(results.report()).contains("RESPONSE", "Response", "a1", "[1]")
        }
    }

    @Test
    fun `empty direct list remains valid and union with no empty member rejects empty`() {
        listOf("""<xs:list itemType="tns:Code"/>""" to 200, UNION to 400).forEach { (definition, status) ->
            val feature = load(definition)
            HttpStub(feature, port = testPort()).use { stub ->
                val response = stub.client.execute(request(""))
                assertThat(response.status).withFailMessage(response.body.toStringLiteral()).isEqualTo(status)
                if (status == 400) assertThat(response.body.toStringLiteral()).contains("Request", "XSD union", "xs:boolean", "tns:Code")
            }
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200, mapOf(CONTENT_TYPE to "text/xml"), StringValue(soap("Response", "")))
            })
            assertThat(results.success()).withFailMessage(results.report()).isEqualTo(status == 200)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sequence", "choice", "all"])
    fun `empty union keeps required structure and occurrence checks`(group: String) {
        val definition = """<xs:union memberTypes="xs:boolean tns:Codes"/>"""
        val extra = """<xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>"""
        val document = schema(definition, true, extra).replace("xs:sequence", "xs:$group")
            .replace("</xs:complexType>", """<xs:attribute name="id" type="xs:string" use="required"/></xs:complexType>""")
        val feature = parseContractFileToFeature(directory.resolve("structure.wsdl").apply { writeText(wsdl(document)) })
        fun payload(name: String) = soap(name, "", true).replace("<t:$name>", """<t:$name id="ok">""")
        val valid = payload("Request")
        val invalid = listOf(
            valid.replace("<t:code></t:code>", ""),
            valid.replace("t:code", "t:wrong"),
            valid.replace("<t:code>", """<t:code xmlns:t="urn:wrong">"""),
            valid.replace(" id=\"ok\"", ""),
            valid.replace("</t:code>", "</t:code><t:code/>")
        )
        HttpStub(feature, port = testPort()).use { stub ->
            assertThat(stub.client.execute(request("").copy(body = StringValue(valid))).status).isEqualTo(200)
            invalid.forEach { xml ->
                val response = stub.client.execute(request("").copy(body = StringValue(xml)))
                assertThat(response.status).withFailMessage("Accepted $xml").isEqualTo(400)
                assertThat(response.body.toStringLiteral()).contains("Request")
            }
        }
        (listOf(payload("Response") to true) + invalid.map { it.replace("t:Request", "t:Response") to false }).forEach { (xml, expected) ->
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200, mapOf(CONTENT_TYPE to "text/xml"), StringValue(xml))
            })
            assertThat(results.success()).withFailMessage(results.report()).isEqualTo(expected)
        }
    }

    @Test
    fun `explicit empty request example reaches provider without filling its value and mock fallback remains authoritative`() {
        val definition = """<xs:union memberTypes="xs:boolean tns:Codes"/>"""
        val extra = """<xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>"""
        val feature = load(definition, extraDefinitions = extra)
        val responseXml = soap("Response", "BA")
        val example = io.specmatic.mock.ScenarioStub(request(""), HttpResponse(200,
            mapOf(CONTENT_TYPE to "text/xml"), StringValue(responseXml)))
        directory.resolve("named_examples").apply { mkdirs() }.resolve("empty.json")
            .writeText(example.withName("empty union request").toJSON().toStringLiteral())
        val withExamples = feature.loadExternalisedExamples()
        assertThat(withExamples.scenarios.flatMap { it.examples }.flatMap { it.rows }).hasSize(1)
        var calls = 0
        val results = withExamples.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(InputSource(StringReader(request.body.toStringLiteral())))
                assertThat(document.getElementsByTagNameNS(NAMESPACE, "Request").item(0).textContent).isEmpty()
                assertSchemaValidPayload(request.body.toStringLiteral(), definition, false, extra)
                calls++
                return example.response
            }
        })
        assertThat(results.success()).withFailMessage(results.report()).isTrue()
        assertThat(calls).isEqualTo(1)
        HttpStub(feature, scenarioStubs = listOf(example), port = testPort()).use { stub ->
            val matched = stub.client.execute(request(""))
            assertThat(matched.body.toStringLiteral()).contains("<t:Response>BA</t:Response>")
            assertThat(matched.headers["X-Specmatic-Type"]).isNotEqualTo("random")
            val fallback = stub.client.execute(request("true"))
            assertThat(fallback.status).isEqualTo(200)
            assertThat(fallback.headers["X-Specmatic-Type"]).isEqualTo("random")
            assertThat(stub.client.execute(request("bad")).status).isEqualTo(400)
        }
        HttpStub(feature, scenarioStubs = listOf(example), port = testPort(), strictMode = true).use { stub ->
            assertThat(stub.client.execute(request("")).status).isEqualTo(200)
            assertThat(stub.client.execute(request("true")).status).isEqualTo(400)
        }
    }

    @Test
    fun `optional union element may be absent while required union element cannot`() {
        val definition = """<xs:union memberTypes="xs:boolean tns:Codes"/>"""
        val extra = """<xs:simpleType name="Codes"><xs:list itemType="tns:Code"/></xs:simpleType>"""
        listOf(false, true).forEach { optional ->
            val document = schema(definition, true, extra).let {
                if (optional) it.replace("name=\"code\"", "name=\"code\" minOccurs=\"0\"") else it
            }
            val feature = parseContractFileToFeature(directory.resolve("optional.wsdl").apply { writeText(wsdl(document)) })
            HttpStub(feature, port = testPort()).use { stub ->
                val body = soap("Request", "", true).replace("<t:code></t:code>", "")
                assertThat(stub.client.execute(request("").copy(body = StringValue(body))).status).isEqualTo(if (optional) 200 else 400)
            }
            val results = feature.executeTests(object : TestExecutor {
                override fun execute(request: HttpRequest) = HttpResponse(200, mapOf(CONTENT_TYPE to "text/xml"),
                    StringValue(soap("Response", "", true).replace("<t:code></t:code>", "")))
            })
            assertThat(results.success()).withFailMessage(results.report()).isEqualTo(optional)
        }
    }

    private fun assertSchemaValidPayload(xml: String, definition: String, nested: Boolean, extra: String = "") {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val body = document.getElementsByTagNameNS(SOAP_NAMESPACE, "Body").item(0)
        val payload = (0 until body.childNodes.length).map { body.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().single()
        val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).apply {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        schemaFactory.newSchema(StreamSource(StringReader(schema(definition, nested, extra))))
            .newValidator().validate(DOMSource(payload))
    }

    private fun load(definition: String, nested: Boolean = false, extraDefinitions: String = "") = parseContractFileToFeature(directory.resolve("named.wsdl").apply {
        writeText(wsdl(schema(definition, nested, extraDefinitions)))
    })

    private fun request(value: String, nested: Boolean = false) = HttpRequest("POST", "/inline", headers = mapOf(CONTENT_TYPE to "text/xml", "SOAPAction" to "/inline/submit"), body = StringValue(soap("Request", value, nested)))

    companion object {
        private const val NAMESPACE = "urn:named-simple-types"
        private const val SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val CODE = """<xs:simpleType name="CodeBase"><xs:restriction base="xs:string"><xs:minLength value="2"/><xs:maxLength value="2"/><xs:pattern value="[A-Z]+"/></xs:restriction></xs:simpleType>
            <xs:simpleType name="Code"><xs:restriction base="tns:CodeBase"/></xs:simpleType>"""
        private const val UNION = """<xs:union memberTypes="xs:boolean tns:Code"/>"""
        private fun soap(name: String, value: String, nested: Boolean = false): String {
            val content = if (nested) "<t:code>$value</t:code>" else value
            return """<s:Envelope xmlns:s="$SOAP_NAMESPACE" xmlns:t="$NAMESPACE"><s:Body><t:$name>$content</t:$name></s:Body></s:Envelope>"""
        }
        private fun declaration(name: String, nested: Boolean) = if (nested)
            """<xs:element name="$name"><xs:complexType><xs:sequence><xs:element name="code" type="tns:Values"/></xs:sequence></xs:complexType></xs:element>"""
            else """<xs:element name="$name" type="tns:Values"/>"""
        private fun schema(definition: String, nested: Boolean = false, extraDefinitions: String = "") = """<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$NAMESPACE" targetNamespace="$NAMESPACE" elementFormDefault="qualified">
            $CODE
            $extraDefinitions
            <xs:simpleType name="Values">$definition</xs:simpleType>
            ${declaration("Request", nested)}
            ${declaration("Response", nested)}
        </xs:schema>"""
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
