package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class QualifiedNamespaceTest {
    private val dummyNode = toXMLNode("<dummy/>")
    @Test
    fun `gets the node name for a qualified element`() {
        val wsdl = mockk<WSDL>()
        every {
            wsdl.mapNamespaceToPrefix(any())
        }.returns("ns1000")

        val qualification = QualifiedNamespace(
            toXMLNode("<xsd:element xmlns:ns0=\"http://localhost\" name=\"ns:Customer\"/>"),
            dummyNode,
            "ns0:Person",
            wsdl
        )
        assertThat(qualification.nodeName).isEqualTo("ns1000:Customer")
    }

    @Test
    fun `a qualified element with no namespace in the wsdl type reference gets no namespace`() {
        val namespace = "http://namespace"
        val schema = toXMLNode("<schema targetNamespace=\"$namespace\" />")
        val wsdl = mockk<WSDL>()
        every {
            wsdl.mapNamespaceToPrefix(namespace)
        } returns "ns"

        val qualification = QualifiedNamespace(
            toXMLNode("<xsd:element name=\"ns:Customer\"/>"),
            schema,
            "Person",
            wsdl
        )
        assertThat(qualification.namespacePrefix).isEqualTo(listOf("ns"))
    }

    @Test
    fun `a qualified element with a namespace in the wsdl type reference`() {
        val wsdl: WSDL = mockk()

        val element = toXMLNode("<xsd:element xmlns:ns0=\"http://localhost\" name=\"ns:Customer\"/>")
        every {
            wsdl.mapNamespaceToPrefix("http://localhost")
        }.returns("ns1")

        val qualification = QualifiedNamespace(element, dummyNode, "ns0:Person", wsdl)
        assertThat(qualification.namespacePrefix).isEqualTo(listOf("ns1"))
    }
}
