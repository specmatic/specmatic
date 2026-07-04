package io.specmatic.core.wsdl.parser.message

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.SOAPMessageType
import io.specmatic.core.wsdl.parser.TYPE_NODE_NAME
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo
import io.specmatic.core.wsdl.payload.SOAPPayload

internal class ComplexElementTest {
    @Test
    fun `does not recurse`() {
        val element = toXMLNode("<xsd:element type=\"ns0:Person\"/>")
        val wsdl = mockk<WSDL>()

        every {
            wsdl.getQualification(element, "ns0:PersonRequest")
        } returns UnqualifiedNamespace("Person")

        val complexElement = ComplexElement("ns0:PersonRequest", element, wsdl)
        val preExistingTypes = mapOf("Name" to XMLPattern("<name>(string)</name>"))
        val wsdlTypeInfo = complexElement.deriveSpecmaticTypes("PersonRequest", preExistingTypes, setOf("PersonRequest"))
        val recursiveNode = toXMLNode("<Person $TYPE_ATTRIBUTE_NAME=\"PersonRequest\"/>")

        assertThat(wsdlTypeInfo).isEqualTo(
            WSDLTypeInfo(
                nodes = listOf(recursiveNode),
                members = listOf(XMLPattern(recursiveNode)),
                types = preExistingTypes
            )
        )
    }

    @Test
    fun `returns types`() {
        val element = toXMLNode("<xsd:element type=\"ns0:Person\"/>").withPrimitiveNamespace()

        val wsdl = mockk<WSDL>()

        val complexType2 = mockk<ComplexType>()
        every {
            complexType2.generateChildren(any(), any(), any())
        } returns listOf(WSDLTypeInfo(listOf(toXMLNode("<data>(string)</data>"))))
        every {
            complexType2.getAttributes()
        } returns emptyList()
        every {
            complexType2.getAttributeWildcards()
        } returns emptyList()
        every {
            complexType2.complexType
        } returns toXMLNode("<complexType/>")
        every {
            wsdl.getComplexTypeNode(element)
        } returns complexType2

        every {
            wsdl.getQualification(element, "ns0:PersonRequest")
        } returns UnqualifiedNamespace("Person")

        every {
            wsdl.getWSDLElementType(any(), any())
        } returns InlineType("TypeName", toXMLNode("<element name=\"data\" type=\"xsd:string\" />").withPrimitiveNamespace(), wsdl)

        val complexElement = ComplexElement("ns0:PersonRequest", element, wsdl)
        val wsdlTypeInfo = complexElement.deriveSpecmaticTypes("PersonRequest", emptyMap(), emptySet())

        val expected = WSDLTypeInfo(
            nodes = listOf(toXMLNode("<Person $TYPE_ATTRIBUTE_NAME=\"PersonRequest\"/>")),
            members = listOf(XMLPattern(toXMLNode("<Person $TYPE_ATTRIBUTE_NAME=\"PersonRequest\"/>"))),
            types = mapOf("PersonRequest" to XMLPattern("<$TYPE_NODE_NAME><data>(string)</data></$TYPE_NODE_NAME>"))
        )
        assertThat(wsdlTypeInfo).isEqualTo(expected)
    }

    @Test
    fun `complex node with no children returns no children`() {
        val typeInfo = ComplexElement("", mockk(), mockk()).generateChildren("", toXMLNode("<complexType/>"), emptyMap(), emptySet())
        assertThat(typeInfo).containsExactly(WSDLTypeInfo())
    }

    @Test
    fun `passes accumulated child types to later child derivation`() {
        val complexType = toXMLNode("""
            <complexType>
                <element name="first"/>
                <element name="second"/>
            </complexType>
        """.trimIndent())

        val wsdl = mockk<WSDL>()
        val firstElement = CapturingWSDLElement(
            WSDLTypeInfo(
                nodes = listOf(toXMLNode("<first/>")),
                types = mapOf("SharedType" to XMLPattern(toXMLNode("<shared/>")))
            )
        )
        val secondElement = CapturingWSDLElement(WSDLTypeInfo(nodes = listOf(toXMLNode("<second/>"))))
        val childTypes = listOf(
            CapturingChildElementType("FirstType", firstElement),
            CapturingChildElementType("SecondType", secondElement)
        )
        var childIndex = 0

        every {
            wsdl.getWSDLElementType("ParentType", any())
        } answers {
            childTypes[childIndex++]
        }

        val typeInfos = ComplexElement("", mockk(), wsdl).generateChildren(
            parentTypeName = "ParentType",
            complexType = complexType,
            existingTypes = emptyMap(),
            typeStack = emptySet()
        )

        assertThat(secondElement.existingTypesSeen).containsKey("SharedType")
        assertThat(secondElement.typeStackSeen).contains("SharedType")
        assertThat(typeInfos.single().types).containsKey("SharedType")
    }

    @Test
    fun `trims whitespace from specmatic type name in generated XML node`() {
        val element = toXMLNode("<xsd:element type=\"ns0:Person\"/>").withPrimitiveNamespace()

        val wsdl = mockk<WSDL>()

        val complexType2 = mockk<ComplexType>()
        every {
            complexType2.generateChildren(any(), any(), any())
        } returns listOf(WSDLTypeInfo(listOf(toXMLNode("<data>(string)</data>"))))
        every {
            complexType2.getAttributes()
        } returns emptyList()
        every {
            complexType2.getAttributeWildcards()
        } returns emptyList()
        every {
            complexType2.complexType
        } returns toXMLNode("<complexType/>")
        every {
            wsdl.getComplexTypeNode(element)
        } returns complexType2

        every {
            wsdl.getQualification(element, "ns0:PersonRequest")
        } returns UnqualifiedNamespace("Person")

        every {
            wsdl.getWSDLElementType(any(), any())
        } returns InlineType("TypeName", toXMLNode("<element name=\"data\" type=\"xsd:string\" />").withPrimitiveNamespace(), wsdl)

        val complexElement = ComplexElement("ns0:PersonRequest", element, wsdl)
        // Use a type name with whitespace
        val wsdlTypeInfo = complexElement.deriveSpecmaticTypes("PersonRequest  ", emptyMap(), emptySet())

        // Verify the generated node has trimmed whitespace in the type attribute
        val generatedNode = wsdlTypeInfo.nodes.first() as XMLNode
        val typeAttributeValue = generatedNode.attributes[TYPE_ATTRIBUTE_NAME]
        assertThat(typeAttributeValue?.toStringLiteral()).isEqualTo("PersonRequest")
        assertThat(typeAttributeValue?.toStringLiteral()).doesNotContain(" ")
    }
}

private data class CapturingChildElementType(
    private val specmaticTypeName: String,
    private val wsdlElement: WSDLElement
) : ChildElementType {
    override fun getWSDLElement(): Pair<String, WSDLElement> = Pair(specmaticTypeName, wsdlElement)
}

private class CapturingWSDLElement(private val typeInfo: WSDLTypeInfo) : WSDLElement {
    var existingTypesSeen: Map<String, Pattern> = emptyMap()
        private set
    var typeStackSeen: Set<String> = emptySet()
        private set

    override fun deriveSpecmaticTypes(
        specmaticTypeName: String,
        existingTypes: Map<String, Pattern>,
        typeStack: Set<String>
    ): WSDLTypeInfo {
        existingTypesSeen = existingTypes
        typeStackSeen = typeStack
        return typeInfo
    }

    override fun getSOAPPayload(
        soapMessageType: SOAPMessageType,
        nodeNameForSOAPBody: String,
        specmaticTypeName: String,
        namespaces: Map<String, String>,
        typeInfo: WSDLTypeInfo
    ): SOAPPayload {
        throw UnsupportedOperationException("Not needed by this test")
    }
}

internal fun XMLNode.withPrimitiveNamespace(): XMLNode {
    return this.copy(namespaces = mapOf("xsd" to primitiveNamespace))
}
