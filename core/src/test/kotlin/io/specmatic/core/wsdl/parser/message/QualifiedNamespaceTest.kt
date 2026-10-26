package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class QualifiedNamespaceTest {
    @Test
    fun `gets the node name from the declaring schema target namespace`() {
        val namespace = "http://example.com/customers"
        val schema = toXMLNode("<schema targetNamespace=\"$namespace\" />")
        val wsdl = mockk<WSDL>()
        every {
            wsdl.mapNamespaceToPrefix(namespace)
        }.returns("ns1000")

        val qualification = QualifiedNamespace(
            toXMLNode("<xsd:element name=\"Customer\"/>"),
            schema,
            "model:PersonType",
            wsdl
        )
        assertThat(qualification.nodeName).isEqualTo("ns1000:Customer")
    }

    @Test
    fun `namespace prefix comes from the declaring schema even when the type reference has another namespace`() {
        val namespace = "http://namespace"
        val schema = toXMLNode("<schema targetNamespace=\"$namespace\" />")
        val wsdl = mockk<WSDL>()
        every {
            wsdl.mapNamespaceToPrefix(namespace)
        } returns "ns"

        val qualification = QualifiedNamespace(
            toXMLNode("<xsd:element name=\"Customer\"/>"),
            schema,
            "other:PersonType",
            wsdl
        )
        assertThat(qualification.namespacePrefix).isEqualTo(listOf("ns"))
    }
}
