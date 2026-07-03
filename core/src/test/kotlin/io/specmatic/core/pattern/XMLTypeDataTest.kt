package io.specmatic.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class XMLTypeDataTest {
    @Test
    fun `prints a WSDL type name without a prefix using the namespace fallback`() {
        val typeName = WSDLTypeName("http://example.com/animals", "Animal")

        assertThat(typeName.displayNameForError()).isEqualTo("Animal (namespace: http://example.com/animals)")
    }

    @Test
    fun `prints a WSDL type name without a prefix or namespace using the local name`() {
        val typeName = WSDLTypeName("", "Animal")

        assertThat(typeName.displayNameForError()).isEqualTo("Animal")
    }

    @Test
    fun `prints an empty XML type correctly`() {
        assertThat(XMLTypeData("person", "person").toGherkinString()).isEqualTo("<person/>")
    }

    @Test
    fun `prints an XML type with text contents correctly`() {
        assertThat(XMLTypeData("person", "person", nodes = listOf(StringPattern())).toGherkinString()).isEqualTo("<person>(string)</person>")
    }

    @Test
    fun `prints an XML type with attributes correctly`() {
        assertThat(XMLTypeData("person", "person", attributes = mapOf("hello" to StringPattern())).toGherkinString()).isEqualTo("<person hello=\"(string)\"/>")
    }

    @Test
    fun `prints an XML type with child nodes correctly`() {
        val xmlType = "<person><name><firstName>(string)</firstName></name></person>"
        val xmlPattern = XMLPattern(xmlType)

        assertThat(xmlPattern.toGherkinString("  ")).isEqualTo("<person>\n  <name>\n    <firstName>(string)</firstName>\n  </name>\n</person>")
    }
}
