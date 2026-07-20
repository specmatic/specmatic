package io.specmatic.core.wsdl

import io.ktor.http.ContentType
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.Result
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class WSDLComprehensionTest {
    @Test
    fun `toFeature generates SOAP request and response bodies for primitive message elements`() {
        val feature = wsdlFeature(
            requestDeclaration = """<xsd:element name="Request" type="xsd:integer"/>""",
            responseDeclaration = """<xsd:element name="Response" type="xsd:boolean"/>""",
        )
        val scenario = feature.scenarios.single()

        val request = scenario.generateHttpRequest(feature.flagsBased)
        val response = scenario.generateHttpResponse()

        assertThat(request.body.toStringLiteral()).contains("<Request>").containsPattern("<Request>-?\\d+</Request>")
        assertThat(response.body.toStringLiteral()).contains("<Response>").containsPattern("<Response>(true|false)</Response>")
        assertThat(scenario.matches(request, scenario.resolver)).isInstanceOf(Result.Success::class.java)
        assertThat(scenario.matches(response, scenario.resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `toFeature enforces constraints declared by a named simple type`() {
        val feature = wsdlFeature(
            requestDeclaration = """<xsd:element name="Request" type="tns:ShortText"/>""",
            additionalSchema = """
                <xsd:simpleType name="ShortText">
                    <xsd:restriction base="xsd:string">
                        <xsd:maxLength value="1"/>
                    </xsd:restriction>
                </xsd:simpleType>
            """.trimIndent(),
        )

        assertRequestMatches(feature, primitiveRequestBody("A"))
        assertRequestDoesNotMatch(feature, primitiveRequestBody("AB"))
    }

    @Test
    fun `toFeature enforces an inline complex type in the SOAP request body`() {
        val feature = wsdlFeature(
            requestDeclaration = complexRequestDeclaration(
                """
                <xsd:element name="Id" type="xsd:integer"/>
                <xsd:element name="Name" type="xsd:string"/>
                """.trimIndent()
            )
        )

        assertRequestMatches(feature, requestBody("<Id>10</Id><Name>Jane</Name>"))
        assertRequestDoesNotMatch(feature, requestBody("<Id>10</Id>"))
        assertRequestDoesNotMatch(feature, requestBody("<Id>not-a-number</Id><Name>Jane</Name>"))
    }

    @Test
    fun `toFeature enforces a referenced complex type in the SOAP request body`() {
        val feature = wsdlFeature(
            requestDeclaration = """<xsd:element name="Request" type="tns:Person"/>""",
            additionalSchema = """
                <xsd:complexType name="Person">
                    <xsd:sequence>
                        <xsd:element name="Id" type="xsd:integer"/>
                        <xsd:element name="Name" type="xsd:string"/>
                    </xsd:sequence>
                </xsd:complexType>
            """.trimIndent(),
        )

        assertRequestMatches(feature, requestBody("<Id>10</Id><Name>Jane</Name>"))
        assertRequestDoesNotMatch(feature, requestBody("<Name>Jane</Name>"))
        assertRequestDoesNotMatch(feature, requestBody("<Id>not-a-number</Id><Name>Jane</Name>"))
    }

    @Test
    fun `toFeature requires an attribute declared with use required`() {
        val feature = wsdlFeature(
            requestDeclaration = attributedRequestDeclaration("""<xsd:attribute name="age" type="xsd:integer" use="required"/>""")
        )

        assertRequestMatches(feature, requestBody("<Name>Jane</Name>", "age=\"30\""))
        assertRequestDoesNotMatch(feature, requestBody("<Name>Jane</Name>"))
        assertRequestDoesNotMatch(feature, requestBody("<Name>Jane</Name>", "age=\"unknown\""))
    }

    @Test
    fun `toFeature permits omission of an optional attribute and validates it when present`() {
        val feature = wsdlFeature(
            requestDeclaration = attributedRequestDeclaration("""<xsd:attribute name="age" type="xsd:integer"/>""")
        )

        assertRequestMatches(feature, requestBody("<Name>Jane</Name>"))
        assertRequestMatches(feature, requestBody("<Name>Jane</Name>", "age=\"30\""))
        assertRequestDoesNotMatch(feature, requestBody("<Name>Jane</Name>", "age=\"unknown\""))
    }

    @Test
    fun `toFeature permits omission of a scalar child with minOccurs zero`() {
        val optionalFeature = wsdlFeature(
            requestDeclaration = complexRequestDeclaration("""<xsd:element name="Name" type="xsd:string" minOccurs="0"/>""")
        )
        val requiredFeature = wsdlFeature(
            requestDeclaration = complexRequestDeclaration("""<xsd:element name="Name" type="xsd:string"/>""")
        )

        assertRequestMatches(optionalFeature, requestBody(""))
        assertRequestMatches(optionalFeature, requestBody("<Name>Jane</Name>"))
        assertRequestDoesNotMatch(requiredFeature, requestBody(""))
    }

    @Test
    fun `toFeature permits repeated scalar children with maxOccurs unbounded`() {
        val repeatingFeature = wsdlFeature(
            requestDeclaration = complexRequestDeclaration("""<xsd:element name="Name" type="xsd:string" maxOccurs="unbounded"/>""")
        )
        val boundedFeature = wsdlFeature(
            requestDeclaration = complexRequestDeclaration("""<xsd:element name="Name" type="xsd:string" maxOccurs="1"/>""")
        )
        val repeatedNames = requestBody("<Name>Jane</Name><Name>John</Name>")

        assertRequestMatches(repeatingFeature, repeatedNames)
        assertRequestDoesNotMatch(boundedFeature, repeatedNames)
    }

    @Test
    fun `toFeature permits omission of a complex child with minOccurs zero`() {
        val optionalFeature = complexChildFeature("minOccurs=\"0\"")
        val requiredFeature = complexChildFeature("")

        assertRequestMatches(optionalFeature, requestBody(""))
        assertRequestMatches(optionalFeature, requestBody(person("10", "Jane")))
        assertRequestDoesNotMatch(requiredFeature, requestBody(""))
    }

    @Test
    fun `toFeature permits repeated complex children with maxOccurs unbounded`() {
        val repeatingFeature = complexChildFeature("maxOccurs=\"unbounded\"")
        val boundedFeature = complexChildFeature("maxOccurs=\"1\"")
        val repeatedPeople = requestBody(person("10", "Jane") + person("20", "John"))

        assertRequestMatches(repeatingFeature, repeatedPeople)
        assertRequestDoesNotMatch(boundedFeature, repeatedPeople)
        assertRequestDoesNotMatch(repeatingFeature, requestBody(person("10", "Jane") + person("invalid", "John")))
    }

    @ParameterizedTest
    @MethodSource("qualifiedElementForms")
    fun `toFeature qualifies SOAP body elements when schema qualification rules require it`(
        elementFormDefault: String,
        form: String,
    ) {
        val feature = primitiveElementFeature(elementFormDefault, form)
        val generatedBody = feature.scenarios.single().generateHttpRequest(feature.flagsBased).body.toStringLiteral()

        assertThat(generatedBody)
            .contains("$NAMESPACE\"")
            .containsPattern("<[A-Za-z0-9_-]+:Request>")
    }

    @ParameterizedTest
    @MethodSource("unqualifiedElementForms")
    fun `toFeature leaves SOAP body elements unqualified when schema qualification rules require it`(
        elementFormDefault: String,
        form: String,
    ) {
        val feature = primitiveElementFeature(elementFormDefault, form)
        val generatedBody = feature.scenarios.single().generateHttpRequest(feature.flagsBased).body.toStringLiteral()

        assertThat(generatedBody)
            .contains("<Request>")
            .doesNotContainPattern("<[A-Za-z0-9_-]+:Request>")
    }

    private fun assertRequestMatches(feature: Feature, body: String) {
        val scenario = feature.scenarios.single()
        assertThat(scenario.matches(soapRequest(body), scenario.resolver)).isInstanceOf(Result.Success::class.java)
    }

    private fun assertRequestDoesNotMatch(feature: Feature, body: String) {
        val scenario = feature.scenarios.single()
        assertThat(scenario.matches(soapRequest(body), scenario.resolver)).isInstanceOf(Result.Failure::class.java)
    }

    private fun primitiveElementFeature(elementFormDefault: String, form: String): Feature = wsdlFeature(
        requestDeclaration = """<xsd:element name="Request" type="xsd:string" $form/>""",
        responseDeclaration = """<xsd:element name="Response" type="xsd:string" $form/>""",
        schemaAttributes = elementFormDefault,
    )

    private fun complexChildFeature(occurrence: String): Feature = wsdlFeature(
        requestDeclaration = complexRequestDeclaration("""<xsd:element name="Person" type="tns:Person" $occurrence/>"""),
        additionalSchema = """
            <xsd:complexType name="Person">
                <xsd:sequence>
                    <xsd:element name="Id" type="xsd:integer"/>
                    <xsd:element name="Name" type="xsd:string"/>
                </xsd:sequence>
            </xsd:complexType>
        """.trimIndent(),
    )

    private fun wsdlFeature(
        requestDeclaration: String,
        responseDeclaration: String = """<xsd:element name="Response" type="xsd:string"/>""",
        additionalSchema: String = "",
        schemaAttributes: String = "",
    ): Feature {
        val wsdl = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="$NAMESPACE"
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

        return WSDL(toXMLNode(wsdl), "comprehension.wsdl").toFeature("comprehension.wsdl")
    }

    private fun soapRequest(body: String): HttpRequest = HttpRequest(
        method = "POST",
        path = PATH,
        headers = mapOf("SOAPAction" to "\"$SOAP_ACTION\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
        body = StringValue(body),
    )

    private fun primitiveRequestBody(value: String): String = soapEnvelope("<Request>$value</Request>")

    private fun requestBody(children: String, attributes: String = ""): String =
        soapEnvelope("<Request $attributes>$children</Request>")

    private fun soapEnvelope(body: String): String = """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="$NAMESPACE">
            <soapenv:Body>$body</soapenv:Body>
        </soapenv:Envelope>
    """.trimIndent()

    private fun person(id: String, name: String): String = "<Person><Id>$id</Id><Name>$name</Name></Person>"

    private fun complexRequestDeclaration(children: String): String = """
        <xsd:element name="Request">
            <xsd:complexType>
                <xsd:sequence>$children</xsd:sequence>
            </xsd:complexType>
        </xsd:element>
    """.trimIndent()

    private fun attributedRequestDeclaration(attribute: String): String = """
        <xsd:element name="Request">
            <xsd:complexType>
                <xsd:sequence><xsd:element name="Name" type="xsd:string"/></xsd:sequence>
                $attribute
            </xsd:complexType>
        </xsd:element>
    """.trimIndent()

    companion object {
        private const val NAMESPACE = "http://specmatic.io/wsdl-comprehension"
        private const val PATH = "/comprehension"
        private const val OPERATION = "Comprehend"
        private const val SOAP_ACTION = "$PATH/$OPERATION"

        @JvmStatic
        fun qualifiedElementForms(): Stream<org.junit.jupiter.params.provider.Arguments> = Stream.of(
            arguments("elementFormDefault=\"qualified\"", ""),
            arguments("elementFormDefault=\"qualified\"", "form=\"qualified\""),
            arguments("elementFormDefault=\"unqualified\"", "form=\"qualified\""),
            arguments("", "form=\"qualified\""),
        )

        @JvmStatic
        fun unqualifiedElementForms(): Stream<org.junit.jupiter.params.provider.Arguments> = Stream.of(
            arguments("elementFormDefault=\"unqualified\"", ""),
            arguments("elementFormDefault=\"unqualified\"", "form=\"unqualified\""),
            arguments("elementFormDefault=\"qualified\"", "form=\"unqualified\""),
            arguments("", "form=\"unqualified\""),
        )
    }
}
