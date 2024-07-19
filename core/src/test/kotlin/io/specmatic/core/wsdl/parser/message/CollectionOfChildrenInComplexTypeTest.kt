package io.specmatic.core.wsdl.parser.message

import io.mockk.every
import io.mockk.mockk
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class CollectionOfChildrenInComplexTypeTest {
    @Test
    fun `should generate child nodes`() {
        val sequence = toXMLNode("<sequence><element name=\"data\" type=\"xsd:string\" /></sequence>")
        val parentTypeName = "ParentType"

        val wsdl = mockk<WSDL>()

        val typeReference = mockk<TypeReference>()
        val element = toXMLNode("<element name=\"data\" type=\"xsd:string\"/>")
        every {
            typeReference.getWSDLElement()
        } returns Pair("xsd_string", SimpleElement("xsd:string", element, wsdl))

        every {
            wsdl.getWSDLElementType("ParentType", element)
        } returns typeReference

        val collection = CollectionOfChildrenInComplexType(sequence, wsdl, parentTypeName)
        val wsdlTypeInfo = collection.process(mockk(), emptyMap(), emptySet())
        val expected = WSDLTypeInfo(listOf(toXMLNode("<data>(string)</data>")))
        assertThat(wsdlTypeInfo).isEqualTo(expected)
    }
}