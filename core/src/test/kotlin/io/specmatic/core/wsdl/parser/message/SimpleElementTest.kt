package io.specmatic.core.wsdl.parser.message

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
}
