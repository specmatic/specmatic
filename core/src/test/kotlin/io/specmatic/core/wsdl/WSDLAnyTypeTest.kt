package io.specmatic.core.wsdl

import io.specmatic.core.*
import io.specmatic.core.pattern.*
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.stub.HttpStub
import io.specmatic.test.TestExecutor
import io.specmatic.testPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

class WSDLAnyTypeTest {
    private val forms = listOf("type=\"xsd:anyType\"", "")
    private val contents = listOf("", "hello", "<u:Unknown xmlns:u=\"urn:unknown\"/>",
        "<u:A xmlns:u=\"urn:unknown\"/><u:B xmlns:u=\"urn:unknown\"/>",
        "before<u:A xmlns:u=\"urn:unknown\"/>after")

    private fun declaration(name: String, form: String, occurrence: String = "") = """<xsd:element name="$name"><xsd:complexType><xsd:sequence>
        <xsd:element name="Payload" $form $occurrence/>
        <xsd:element name="Count" type="xsd:int"/>
    </xsd:sequence></xsd:complexType></xsd:element>"""

    private fun feature(form: String, occurrence: String = "", additional: String = "") = wsdlFeature(
        declaration("Request", form, occurrence), declaration("Response", form, occurrence), additional,
        schemaAttributes = "elementFormDefault=\"qualified\"")

    private fun children(content: String, attributes: String = "") = "<t:Payload $attributes>$content</t:Payload><t:Count>7</t:Count>"

    private fun oracle(form: String, children: String, additional: String = "", occurrence: String = ""): Boolean {
        val schema = """<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:t="$NAMESPACE" xmlns:tns="$NAMESPACE" targetNamespace="$NAMESPACE" elementFormDefault="qualified">
            ${declaration("Request", form, occurrence)}$additional
        </xsd:schema>"""
        val validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
            .newSchema(StreamSource(StringReader(schema))).newValidator()
        return runCatching { validator.validate(StreamSource(StringReader("<t:Request xmlns:t=\"$NAMESPACE\" xmlns:tns=\"$NAMESPACE\">$children</t:Request>"))) }.isSuccess
    }

    private fun assertMatching(feature: Feature, children: String, accepted: Boolean) {
        val scenario = feature.scenarios.single()
        val req = request(children)
        val requestResult = scenario.matches(req, scenario.resolver)
        val responseResult = scenario.httpResponsePattern.matchesResponse(HttpResponse(200,
            headers = mapOf("Content-Type" to "text/xml"), body = StringValue(envelope("<t:Response>$children</t:Response>"))), scenario.resolver)
        for ((direction, result) in listOf("request" to requestResult, "response" to responseResult))
            assertThat(result.isSuccess()).describedAs("%s %s: %s", direction, children, result.reportString()).isEqualTo(accepted)
    }

    @Test
    fun `explicit and implicit anyType accept empty text mixed children and unknown attributes like JDK`() {
        for (form in forms) {
            val feature = feature(form)
            for (content in contents) {
                val children = children(content, "flag=\"neutral\" xmlns:u=\"urn:unknown\" u:flag=\"neutral\"")
                assertThat(oracle(form, children)).describedAs(children).isTrue()
                assertMatching(feature, children, true)
            }
        }
    }

    @Test
    fun `lax assessment validates known global children and attributes including declarations below unknown elements`() {
        val declarations = """<xsd:element name="Known" type="xsd:int"/><xsd:attribute name="score" type="xsd:int"/>"""
        for (form in forms) {
            val feature = feature(form, additional = declarations)
            for ((content, attrs, accepted) in listOf(
                Triple("<t:Known>7</t:Known>", "t:score=\"7\"", true),
                Triple("<t:Known>bad</t:Known>", "", false),
                Triple("", "t:score=\"bad\"", false),
                Triple("<u:Unknown xmlns:u=\"urn:unknown\"><t:Known>bad</t:Known></u:Unknown>", "", false),
                Triple("<u:Unknown xmlns:u=\"urn:unknown\"><t:Known>7</t:Known></u:Unknown>", "", true),
                Triple("<u:Known xmlns:u=\"urn:unknown\">bad</u:Known>", "", true),
                Triple("<u:Unknown xmlns:u=\"urn:unknown\" t:score=\"7\"/>", "", true),
                Triple("<u:Unknown xmlns:u=\"urn:unknown\" t:score=\"bad\"/>", "", false))) {
                val children = children(content, attrs)
                assertThat(oracle(form, children, declarations)).describedAs(children).isEqualTo(accepted)
                assertMatching(feature, children, accepted)
            }
        }
    }

    @Test
    fun `abstract anyType declarations remain prohibited and substitution members remain usable`() {
        for (form in forms) for (abstractValue in listOf("true", "1")) {
            val globals = """<xsd:element name="Head" $form abstract="$abstractValue"/><xsd:element name="Member" type="xsd:string" substitutionGroup="tns:Head"/>"""
            val nested = wsdlFeature("""<xsd:element name="Request"><xsd:complexType><xsd:sequence>
                <xsd:element ref="tns:Head"/><xsd:element name="Count" type="xsd:int"/>
            </xsd:sequence></xsd:complexType></xsd:element>""",
                """<xsd:element name="Response"><xsd:complexType><xsd:sequence>
                <xsd:element ref="tns:Head"/><xsd:element name="Count" type="xsd:int"/>
            </xsd:sequence></xsd:complexType></xsd:element>""", globals, schemaAttributes = "elementFormDefault=\"qualified\"")
            assertMatching(nested, "<t:Head/><t:Count>7</t:Count>", false)
            assertMatching(nested, "<t:Member>neutral</t:Member><t:Count>7</t:Count>", true)
            val lax = feature(form, additional = globals)
            val prohibited = children("<t:Head/>")
            assertThat(oracle(form, prohibited, globals)).isFalse()
            assertMatching(lax, prohibited, false)
        }
    }

    @Test
    fun `parent bounds QName typed sibling and outer structure stay strict`() {
        for (form in forms) {
            val required = feature(form)
            for (children in listOf("<t:Count>7</t:Count>", children("").replace("Count>7", "Count>bad"),
                children("").replace("t:Payload", "Payload"), children("") + "<t:Extra/>",
                "<t:Payload/><t:Payload/><t:Count>7</t:Count>")) {
                assertThat(oracle(form, children)).isFalse()
                assertMatching(required, children, false)
            }
            val optional = feature(form, "minOccurs=\"0\"")
            assertMatching(optional, "<t:Count>7</t:Count>", true)
            assertMatching(optional, children(""), true)
            val prohibited = feature(form, "minOccurs=\"0\" maxOccurs=\"0\"")
            assertMatching(prohibited, "<t:Count>7</t:Count>", true)
            assertMatching(prohibited, children(""), false)
            assertThat(oracle(form, children(""), occurrence = "minOccurs=\"0\" maxOccurs=\"0\"")).isFalse()
            val scenario = required.scenarios.single()
            val wrongEnvelope = request(children("")).let { it.copy(body = StringValue(it.body.toStringLiteral().replace("http://schemas.xmlsoap.org/soap/envelope/", "urn:wrong"))) }
            assertThat(scenario.matches(wrongEnvelope, scenario.resolver).isSuccess()).isFalse()
            val undeclaredHeader = request(children("")).let { it.copy(body = StringValue(it.body.toStringLiteral().replace("<soapenv:Body>", "<soapenv:Header><t:Extra/></soapenv:Header><soapenv:Body>"))) }
            assertThat(scenario.matches(undeclaredHeader, scenario.resolver).isSuccess()).isFalse()
        }
    }

    @Test
    fun `a user defined type named anyType retains its typed structure`() {
        val feature = feature("type=\"tns:anyType\"", additional = """<xsd:complexType name="anyType"><xsd:sequence>
            <xsd:element name="Value" type="xsd:int"/>
        </xsd:sequence></xsd:complexType>""")
        assertMatching(feature, children("<t:Value>7</t:Value>"), true)
        assertMatching(feature, children(""), false)
        assertMatching(feature, children("<t:Value>bad</t:Value>"), false)
    }

    @Test
    fun `annotations do not turn implicit anyType into an inline complex type`() {
        val feature = wsdlFeature("<xsd:element name=\"Request\"><xsd:annotation><xsd:documentation>Neutral</xsd:documentation></xsd:annotation></xsd:element>")
        assertMatching(feature, "hello", true)
    }

    @Test
    fun `xsi type and nil on anyType are explicitly outside the supported subset`() {
        for (form in forms) for (attribute in listOf("type=\"xsd:string\"", "nil=\"true\"")) {
            val children = children("", "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xsi:$attribute")
            assertMatching(feature(form), children, false)
        }
    }

    @Test
    fun `unknown children with xsi type are rejected as unsupported rather than skipped`() {
        for (form in forms) for (value in listOf("7", "bad")) {
            val content = "<u:Unknown xmlns:u=\"urn:unknown\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xsi:type=\"xsd:int\">$value</u:Unknown>"
            assertThat(oracle(form, children(content))).isEqualTo(value == "7")
            assertMatching(feature(form), children(content), false)
        }
    }

    @Test
    fun `reparse compatibility repair completion and example specialization preserve anyType behavior`() {
        for (form in forms) {
            val first = feature(form).scenarios.single()
            val second = feature(form).scenarios.single()
            val body = first.httpRequestPattern.body
            val value = body.parse(request(children(contents.last())).body.toStringLiteral(), first.resolver)
            val compatible = body.encompasses(second.httpRequestPattern.body, first.resolver, second.resolver)
            assertThat(compatible.isSuccess()).describedAs(compatible.reportString()).isTrue()
            assertThat(body.fixValue(value, first.resolver)).isEqualTo(value)
            assertThat(body.fillInTheBlanks(value, first.resolver).value).isEqualTo(value)
            assertThat(body.patternFrom(value, first.resolver).matches(value, first.resolver).isSuccess()).isTrue()
        }
    }

    @Test
    fun `childless implicit declarations load without an inline type`() {
        val feature = wsdlFeature("<xsd:element name=\"Request\"/>", "<xsd:element name=\"Response\"/>")
        assertMatching(feature, "", true)
        assertMatching(feature, contents.last(), true)
    }

    @Test
    fun `top level explicit and childless implicit anyType load match and generate`() {
        for (form in forms) {
            val feature = wsdlFeature("<xsd:element name=\"Request\" $form/>", "<xsd:element name=\"Response\" $form/>")
            for (content in contents) assertMatching(feature, content, true)
            val scenario = feature.scenarios.single()
            val request = scenario.generateHttpRequest()
            assertThat(scenario.matches(request, scenario.resolver).isSuccess()).isTrue()
        }
    }

    @Test
    fun `generated variants choose valid empty anyType content and retain bounds`() {
        for (form in forms) for (occurrence in listOf("", "minOccurs=\"0\"", "minOccurs=\"0\" maxOccurs=\"0\"")) {
            val feature = feature(form, occurrence)
            val scenario = feature.scenarios.single()
            for (body in listOf(scenario.httpRequestPattern.body, scenario.httpResponsePattern.body)) {
                val variants = listOf(body) + body.newBasedOn(Row(), scenario.resolver).map { it.value }.toList() + body.newBasedOn(scenario.resolver).toList()
                for (variant in variants) {
                    val xml = variant.generate(scenario.resolver) as XMLNode
                    assertThat(body.matches(xml, scenario.resolver).isSuccess()).describedAs(xml.toStringLiteral()).isTrue()
                    val payload = (xml.childNodes.filterIsInstance<XMLNode>().single { it.name == "Body" }).childNodes.filterIsInstance<XMLNode>().single()
                    assertThat(oracle(form, payload.childNodes.joinToString("") { it.toStringLiteral() }, occurrence = occurrence)).isTrue()
                }
            }
        }
    }

    @Test
    fun `OSS mock and provider validation exercise valid and invalid controls`() {
        for (form in forms) {
            val feature = feature(form, additional = """<xsd:element name="Known" type="xsd:int"/>""")
            HttpStub(feature, port = testPort()).use { stub ->
                assertThat(stub.client.execute(request(children(contents.last()))).status).isEqualTo(200)
                assertThat(stub.client.execute(request(children("<t:Known>7</t:Known>"))).status).isEqualTo(200)
                assertThat(stub.client.execute(request(children("<t:Known>bad</t:Known>"))).status).isEqualTo(400)
                assertThat(stub.client.execute(request(children("").replace("Count>7", "Count>bad"))).status).isEqualTo(400)
            }
            for ((content, accepted) in listOf(children(contents.last()) to true, children("<t:Known>7</t:Known>") to true,
                children("<t:Known>bad</t:Known>") to false, children("").replace("Count>7", "Count>bad") to false)) {
                val results = feature.executeTests(object : TestExecutor {
                    override fun execute(request: HttpRequest) = HttpResponse(200, headers = mapOf("Content-Type" to "text/xml"),
                        body = StringValue(envelope("<t:Response>$content</t:Response>")))
                })
                assertThat(results.success()).describedAs(results.report()).isEqualTo(accepted)
            }
        }
    }

    private fun request(children: String) = HttpRequest(method = "POST", path = PATH,
        headers = mapOf("SOAPAction" to "\"$SOAP_ACTION\"", "Content-Type" to "text/xml"),
        body = StringValue(envelope("<t:Request>$children</t:Request>")))

    private fun envelope(body: String) = """<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="$NAMESPACE"><soapenv:Body>$body</soapenv:Body></soapenv:Envelope>"""

    private fun wsdlFeature(
        requestDeclaration: String,
        responseDeclaration: String = """<xsd:element name="Response" type="xsd:string"/>""",
        additionalSchema: String = "",
        schemaAttributes: String = "",
        additionalNamespaces: String = "",
        wsdlPath: String = "anytype.wsdl",
    ): Feature {
        val wsdl = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="$NAMESPACE"
                              $additionalNamespaces
                              targetNamespace="$NAMESPACE">
                <wsdl:types>
                    <xsd:schema targetNamespace="$NAMESPACE" $schemaAttributes>
                        $requestDeclaration
                        $responseDeclaration
                        $additionalSchema
                    </xsd:schema>
                </wsdl:types>
                <wsdl:message name="RequestMessage">
                    <wsdl:part name="request" element="tns:Request"/>
                </wsdl:message>
                <wsdl:message name="ResponseMessage">
                    <wsdl:part name="response" element="tns:Response"/>
                </wsdl:message>
                <wsdl:portType name="ServicePortType">
                    <wsdl:operation name="$OPERATION">
                        <wsdl:input message="tns:RequestMessage"/>
                        <wsdl:output message="tns:ResponseMessage"/>
                    </wsdl:operation>
                </wsdl:portType>
                <wsdl:binding name="ServiceBinding" type="tns:ServicePortType">
                    <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="$OPERATION">
                        <soap:operation soapAction="$SOAP_ACTION"/>
                        <wsdl:input><soap:body use="literal"/></wsdl:input>
                        <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                </wsdl:binding>
                <wsdl:service name="Service">
                    <wsdl:port name="ServicePort" binding="tns:ServiceBinding">
                        <soap:address location="http://localhost:9000$PATH"/>
                    </wsdl:port>
                </wsdl:service>
            </wsdl:definitions>
        """.trimIndent()

        return WSDL(toXMLNode(wsdl), wsdlPath).toFeature(wsdlPath)
    }

    companion object {
        private const val NAMESPACE = "urn:anytype"
        private const val PATH = "/anytype"
        private const val OPERATION = "AnyType"
        private const val SOAP_ACTION = "$PATH/$OPERATION"
    }
}
