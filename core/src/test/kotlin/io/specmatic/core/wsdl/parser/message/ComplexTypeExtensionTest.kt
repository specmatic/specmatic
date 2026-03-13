package io.specmatic.core.wsdl.parser.message

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

internal class ComplexTypeExtensionTest {
    @Test
    fun `complex type with extension`() {
        val child = toXMLNode("<xsd:complexContent><xsd:extension base=\"otherComplexType\"><xsd:sequence><element name=\"data2\" type=\"xsd:integer\"></element></xsd:sequence></xsd:extension></xsd:complexContent>")

        val parentComplexType = toXMLNode("<complexType><element name=\"data1\" type=\"xsd:string\"></element></complexType>").withPrimitiveNamespace()

        val wsdl = mockk<WSDL>()
        every {
            wsdl.findTypeFromAttribute(any(), "base")
        }.returns(parentComplexType)
        val simpleElement1 = toXMLNode("<element name=\"data1\" type=\"xsd:string\" />")
        val simpleElement2 = toXMLNode("<element name=\"data2\" type=\"xsd:integer\" />")

        val typeReference1 = mockk<TypeReference>()
        every {
            typeReference1.getWSDLElement()
        } returns Pair("xsd_string", SimpleElement("xsd:string", simpleElement1, wsdl))
        every {
            wsdl.getWSDLElementType("ParentType", simpleElement1)
        }.returns(typeReference1)

        val typeReference2 = mockk<TypeReference>()
        every {
            typeReference2.getWSDLElement()
        } returns Pair("xsd_number", SimpleElement("xsd:number", simpleElement2, wsdl))
        every {
            wsdl.getWSDLElementType("ParentType", simpleElement2)
        }.returns(typeReference2)

        val wsdlTypeInfo = ComplexTypeExtension(child, wsdl, "ParentType").process(listOf(WSDLTypeInfo()), emptyMap(), emptySet())

        val expected = WSDLTypeInfo(listOf(toXMLNode("<data1>(string)</data1>"), toXMLNode("<data2>(number)</data2>")))
        assertThat(wsdlTypeInfo).containsExactly(expected)
    }

    @Test
    fun `complex type which extends but actually does not actually specify any extensions`() {
        val child = toXMLNode("<xsd:complexContent><xsd:extension base=\"otherComplexType\"/></xsd:complexContent>")

        val parentComplexType = toXMLNode("<complexType/>").withPrimitiveNamespace()

        val wsdl = mockk<WSDL>()
        every {
            wsdl.findTypeFromAttribute(any(), "base")
        }.returns(parentComplexType)

        val wsdlTypeInfo = ComplexTypeExtension(child, wsdl, "ParentType").process(listOf(WSDLTypeInfo()), emptyMap(), emptySet())

        assertThat(wsdlTypeInfo).containsExactly(WSDLTypeInfo())
    }
}
