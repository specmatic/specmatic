package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.withPatternDelimiters
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
        val addressPattern = typeInfo.types.getValue("tns_AddressType") as XMLPattern
        val concreteAddressPattern = addressPattern.copy(
            pattern = addressPattern.pattern.copy(name = "address", realName = "address")
        )
        val resolver = Resolver(
            newPatterns = typeInfo.types.mapKeys { (name, _) -> withPatternDelimiters(name) }
        )
        val validAddress = toXMLNode(
            """
            <address xmlns:tns="http://example.com/wiring">
                <tns:name>Jane</tns:name>
                <tns:Code>ABC</tns:Code>
                <tns:inlineStatus><tns:level>10</tns:level></tns:inlineStatus>
                <tns:tag>first</tns:tag>
                <tns:tag>second</tns:tag>
                <tns:score/>
            </address>
            """.trimIndent()
        )

        assertThat(typeName).isEqualTo("tns_AddressType")
        assertThat(concreteAddressPattern.matches(validAddress, resolver))
            .isInstanceOf(Result.Success::class.java)
    }

    private fun loadWsdl(path: String): WSDL {
        val wsdlFile = File(path)
        return WSDL(toXMLNode(wsdlFile.readText()), wsdlFile.canonicalPath)
    }
}
