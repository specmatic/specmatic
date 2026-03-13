package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

internal class TypeReferenceTest {
    @Test
    fun `simple type nodes still derive the same downstream payload shape`() {
        val element = toXMLNode("<xsd:element name=\"value\" type=\"xsd:string\" />").copy(namespaces = mapOf("xsd" to "http://www.w3.org/2001/XMLSchema"))
        val typeReference = TypeReference(element, loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl"))
        val (typeName, wsdlElement) = typeReference.getWSDLElement()

        val typeInfo = wsdlElement.deriveSpecmaticTypes(typeName, emptyMap(), emptySet())

        assertThat(typeName).isEqualTo("xsd_string")
        assertThat(typeInfo.nodes).containsExactly(toXMLNode("<value>(string)</value>"))
    }

    @Test
    fun `complex type nodes still derive the same downstream payload shape`() {
        val element = toXMLNode("<xsd:element name=\"address\" type=\"tns:AddressType\" />").copy(namespaces = mapOf("tns" to "http://example.com/wiring"))
        val typeReference = TypeReference(element, loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl"))
        val (typeName, wsdlElement) = typeReference.getWSDLElement()

        val typeInfo = wsdlElement.deriveSpecmaticTypes(typeName, emptyMap(), emptySet())

        assertThat(typeName).isEqualTo("tns_AddressType")
        assertThat((typeInfo.types.getValue("tns_AddressType") as io.specmatic.core.pattern.XMLPattern).toPrettyString())
            .contains("name")
            .contains("(string)")
            .contains("Code")
            .contains("specmatic_occurs=\"multiple\"")
            .contains("specmatic_nillable=\"true\"")
    }

    private fun loadWsdl(path: String): WSDL {
        val wsdlFile = File(path)
        return WSDL(toXMLNode(wsdlFile.readText()), wsdlFile.canonicalPath)
    }
}
