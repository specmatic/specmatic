package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

internal class ElementReferenceTest {
    @Test
    fun `element refs still resolve to the same downstream payload shape`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")
        val schema = wsdl.schemas.getValue("http://example.com/wiring")
        val addressType = schema.findByNodeNameAndAttribute("complexType", "name", "AddressType")
        val codeReference = addressType.findFirstChildByName("sequence")!!.findChildrenByName("element")[1]

        val reference = ElementReference(codeReference, wsdl)
        val (typeName, resolvedElement) = reference.getWSDLElement()
        val typeInfo = resolvedElement.deriveSpecmaticTypes(typeName, emptyMap(), emptySet())
        val node = typeInfo.nodes.single() as io.specmatic.core.value.XMLNode

        assertThat(typeName).isEqualTo("tns_Code")
        assertThat(node.name).isEqualTo("Code")
        assertThat(node.attributes[OCCURS_ATTRIBUTE_NAME]?.toStringLiteral()).isEqualTo(OPTIONAL_ATTRIBUTE_VALUE)
        assertThat(typeInfo.namespacePrefixes).hasSize(1)
    }

    private fun loadWsdl(path: String): WSDL {
        val wsdlFile = File(path)
        return WSDL(toXMLNode(wsdlFile.readText()), wsdlFile.canonicalPath)
    }
}
