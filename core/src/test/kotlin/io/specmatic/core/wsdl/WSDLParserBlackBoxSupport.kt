package io.specmatic.core.wsdl

import io.ktor.http.ContentType
import io.specmatic.conversions.wsdlContentToFeature
import io.specmatic.core.CONTENT_TYPE
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.parseContractFileToFeature
import io.specmatic.core.value.StringValue
import io.specmatic.mock.ScenarioStub
import org.assertj.core.api.Assertions.assertThat
import java.io.File

internal data class WsdlExampleFixture(
    val feature: Feature,
    val scenarioStubs: List<ScenarioStub>,
) {
    val scenarioStub: ScenarioStub
        get() = scenarioStubs.single()
}

internal fun loadWsdlExampleFixture(wsdlPath: String, examplesPath: String): WsdlExampleFixture {
    val wsdlFile = File(wsdlPath)
    val examplesDir = File(examplesPath)
    val feature = parseContractFileToFeature(wsdlFile, exampleDirPaths = listOf(examplesDir.path)).loadExternalisedExamples()
    val scenarioStubs = examplesDir.listFiles().orEmpty().sortedBy(File::getName).map(ScenarioStub::readFromFile)

    return WsdlExampleFixture(feature, scenarioStubs)
}

internal fun assertHttpRequestMatches(actual: HttpRequest, expected: HttpRequest) {
    assertThat(actual.method).isEqualTo(expected.method)
    assertThat(actual.path).isEqualTo(expected.path)
    assertThat(actual.headers["SOAPAction"]).isEqualTo(expected.headers["SOAPAction"])
    assertThat(actual.body).isEqualTo(expected.body)
}

internal fun choiceWsdlFeature(
    requestName: String,
    requestBodySchema: String,
    path: String,
    operation: String,
    namespace: String = "http://choice-edge",
): Feature {
    val wsdlContent = """
        <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                          xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                          xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                          xmlns:tns="$namespace"
                          targetNamespace="$namespace">
            <wsdl:types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:element name="$requestName">
                        <xsd:complexType>
                            <xsd:sequence>
                                $requestBodySchema
                            </xsd:sequence>
                        </xsd:complexType>
                    </xsd:element>
                    <xsd:element name="ChoiceResponse">
                        <xsd:complexType>
                            <xsd:sequence>
                                <xsd:element name="status" type="xsd:string"/>
                            </xsd:sequence>
                        </xsd:complexType>
                    </xsd:element>
                </xsd:schema>
            </wsdl:types>

            <wsdl:message name="ChoiceRequestMessage">
                <wsdl:part name="parameters" element="tns:$requestName"/>
            </wsdl:message>
            <wsdl:message name="ChoiceResponseMessage">
                <wsdl:part name="parameters" element="tns:ChoiceResponse"/>
            </wsdl:message>

            <wsdl:portType name="ChoicePortType">
                <wsdl:operation name="$operation">
                    <wsdl:input message="tns:ChoiceRequestMessage"/>
                    <wsdl:output message="tns:ChoiceResponseMessage"/>
                </wsdl:operation>
            </wsdl:portType>

            <wsdl:binding name="ChoiceBinding" type="tns:ChoicePortType">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="$operation">
                    <soap:operation soapAction="$path/$operation"/>
                    <wsdl:input>
                        <soap:body use="literal"/>
                    </wsdl:input>
                    <wsdl:output>
                        <soap:body use="literal"/>
                    </wsdl:output>
                </wsdl:operation>
            </wsdl:binding>

            <wsdl:service name="ChoiceService">
                <wsdl:port name="ChoicePort" binding="tns:ChoiceBinding">
                    <soap:address location="http://localhost:9000$path"/>
                </wsdl:port>
            </wsdl:service>
        </wsdl:definitions>
    """.trimIndent()

    return wsdlContentToFeature(wsdlContent, "$requestName.wsdl")
}

internal fun choiceSoapRequest(
    requestName: String,
    requestBody: String,
    path: String,
    operation: String,
    namespace: String = "http://choice-edge",
): HttpRequest {
    return HttpRequest(
        method = "POST",
        path = path,
        headers = mapOf("SOAPAction" to "\"$path/$operation\"", CONTENT_TYPE to ContentType.Text.Xml.toString()),
        body = StringValue(
            """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:t="$namespace">
                <soapenv:Body>
                    <t:$requestName>
                        $requestBody
                    </t:$requestName>
                </soapenv:Body>
            </soapenv:Envelope>
            """.trimIndent()
        ),
    )
}

internal fun generatedRequestBodies(feature: Feature): List<String> {
    return feature.scenarios.single()
        .generateTestScenarios(feature.flagsBased)
        .map { it.value.generateHttpRequest(feature.flagsBased).body.toStringLiteral() }
        .toList()
}

internal fun countOccurrences(text: String, token: String): Int {
    return text.windowed(token.length, 1).count { it == token }
}
