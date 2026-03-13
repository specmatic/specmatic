package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.StringPattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.parsedPattern
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.toXMLNode
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class SimpleElementTest {
    @Test
    fun `xsd token is treated as a string primitive`() {
        val element = toXMLNode("<xsd:element name=\"SessionToken\" type=\"xsd:token\" />")
            .copy(namespaces = mapOf("xsd" to primitiveNamespace))

        val typeValue = elementTypeValue(element)
        val (nodes, _) = createSimpleType(element, mockk())

        assertThat(typeValue.toStringLiteral()).isEqualTo("(string)")
        assertThat(nodes).containsExactly(toXMLNode("<SessionToken>(string)</SessionToken>"))
    }

    @Test
    fun `restricted xsd token preserves regex and maxLength in generated xml token`() {
        val simpleTypeNode = toXMLNode(
            """
            <xsd:simpleType xmlns:xsd="http://www.w3.org/2001/XMLSchema" name="ymdDateTime">
                <xsd:restriction base="xsd:token">
                    <xsd:maxLength value="19"/>
                    <xsd:pattern value="[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"/>
                </xsd:restriction>
            </xsd:simpleType>
            """.trimIndent()
        )
        val element = toXMLNode("<xsd:element name=\"ConstrainedTimestamp\" />")
        val simpleElement = SimpleElement("tns:ymdDateTime", element, mockk(), simpleTypeNode = simpleTypeNode)

        val typeInfo = simpleElement.deriveSpecmaticTypes("ConstrainedTimestampType", emptyMap(), emptySet())
        val node = typeInfo.nodes.single().let { it as io.specmatic.core.value.XMLNode }
        val token = node.childNodes.single() as StringValue
        val pattern = parsedPattern(token.toStringLiteral()) as StringPattern
        val xmlPattern = XMLPattern(node)

        assertThat(token.toStringLiteral())
            .contains("maxLength 19")
            .contains("regex [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
        assertThat(pattern.maxLength).isEqualTo(19)
        assertThat(pattern.regex).isEqualTo("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
        assertThat(xmlPattern.matches(node.copy(childNodes = listOf(StringValue("2026-03-13T10:11:12"))), Resolver()))
            .isInstanceOf(Result.Success::class.java)
        assertThat(xmlPattern.matches(node.copy(childNodes = listOf(StringValue("2026-03-13"))), Resolver()))
            .isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `restricted xsd token preserves minLength in generated xml token`() {
        val simpleTypeNode = toXMLNode(
            """
            <xsd:simpleType xmlns:xsd="http://www.w3.org/2001/XMLSchema" name="fixedToken">
                <xsd:restriction base="xsd:token">
                    <xsd:minLength value="3"/>
                    <xsd:maxLength value="5"/>
                </xsd:restriction>
            </xsd:simpleType>
            """.trimIndent()
        )
        val element = toXMLNode("<xsd:element name=\"FixedToken\" />")
        val simpleElement = SimpleElement("tns:fixedToken", element, mockk(), simpleTypeNode = simpleTypeNode)

        val typeInfo = simpleElement.deriveSpecmaticTypes("FixedTokenType", emptyMap(), emptySet())
        val node = typeInfo.nodes.single().let { it as io.specmatic.core.value.XMLNode }
        val token = node.childNodes.single() as StringValue
        val pattern = parsedPattern(token.toStringLiteral()) as StringPattern

        assertThat(token.toStringLiteral()).contains("minLength 3").contains("maxLength 5")
        assertThat(pattern.minLength).isEqualTo(3)
        assertThat(pattern.maxLength).isEqualTo(5)
    }
}
