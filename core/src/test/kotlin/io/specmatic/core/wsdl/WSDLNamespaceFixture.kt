package io.specmatic.core.wsdl

internal class WSDLNamespaceFixture(private val namespace: String, private val path: String) {
    fun schema(declarations: String, defaults: String = ""): String = """
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="$namespace"
            targetNamespace="$namespace" $defaults>$declarations</xs:schema>
    """.trimIndent()

    fun wsdl(schema: String): String = """
        <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
            xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
            xmlns:tns="$namespace" targetNamespace="$namespace">
            <wsdl:types>$schema</wsdl:types>
            <wsdl:message name="Input"><wsdl:part name="body" element="tns:Request"/></wsdl:message>
            <wsdl:message name="Output"><wsdl:part name="body" element="tns:Response"/></wsdl:message>
            <wsdl:portType name="Port"><wsdl:operation name="submit">
                <wsdl:input message="tns:Input"/><wsdl:output message="tns:Output"/>
            </wsdl:operation></wsdl:portType>
            <wsdl:binding name="Binding" type="tns:Port">
                <soap:binding transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="submit"><soap:operation soapAction="$path/submit"/>
                    <wsdl:input><soap:body use="literal"/></wsdl:input>
                    <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
            </wsdl:binding>
            <wsdl:service name="Namespaces"><wsdl:port name="Port" binding="tns:Binding">
                <soap:address location="http://localhost:9000$path"/>
            </wsdl:port></wsdl:service>
        </wsdl:definitions>
    """.trimIndent()
}
