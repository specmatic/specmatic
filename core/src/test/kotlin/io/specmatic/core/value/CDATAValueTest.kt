package io.specmatic.core.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.w3c.dom.CDATASection
import javax.xml.parsers.DocumentBuilderFactory

internal class CDATAValueTest {

    @Nested
    @DisplayName("Construction")
    inner class Construction {
        @Test
        fun `should construct CDATAValue with StringValue`() {
            val stringValue = StringValue("test content")
            val cdataValue = CDATAValue(stringValue)

            assertThat(cdataValue.stringValue).isEqualTo(stringValue)
            assertThat(cdataValue.nativeValue).isEqualTo("test content")
        }

        @Test
        fun `should construct CDATAValue with String`() {
            val cdataValue = CDATAValue("test content")

            assertThat(cdataValue.stringValue).isEqualTo(StringValue("test content"))
            assertThat(cdataValue.nativeValue).isEqualTo("test content")
        }

        @Nested
        @DisplayName("build() method")
        inner class BuildMethod {
            private val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            private val document = documentBuilder.newDocument()

            @Test
            fun `should create CDATA section in XML document`() {
                val cdataValue = CDATAValue("test content")
                val node = cdataValue.build(document)

                assertThat(node).isInstanceOf(CDATASection::class.java)
                assertThat(node.nodeType).isEqualTo(org.w3c.dom.Node.CDATA_SECTION_NODE)
                assertThat(node.textContent).isEqualTo("test content")
            }

            @Test
            fun `should create CDATA section with special XML characters`() {
                val content = "<tag>data</tag>&entity;"
                val cdataValue = CDATAValue(content)
                val node = cdataValue.build(document)

                assertThat(node).isInstanceOf(CDATASection::class.java)
                assertThat(node.textContent).isEqualTo(content)
            }

            @Test
            fun `should create CDATA section with closing sequence`() {
                val content = "data]]>more data"
                val cdataValue = CDATAValue(content)
                val node = cdataValue.build(document)

                assertThat(node).isInstanceOf(CDATASection::class.java)
                assertThat(node.textContent).isEqualTo(content)
            }
        }

        @Nested
        @DisplayName("equals() method")
        inner class EqualsMethod {
            @Test
            fun `should be equal to itself`() {
                val cdataValue = CDATAValue("test")

                assertThat(cdataValue).isEqualTo(cdataValue)
            }

            @Test
            fun `should be equal to another CDATAValue with same content`() {
                val cdataValue1 = CDATAValue("test content")
                val cdataValue2 = CDATAValue("test content")

                assertThat(cdataValue1).isEqualTo(cdataValue2)
            }

            @Test
            fun `should not be equal to CDATAValue with different content`() {
                val cdataValue1 = CDATAValue("content1")
                val cdataValue2 = CDATAValue("content2")

                assertThat(cdataValue1).isNotEqualTo(cdataValue2)
            }

            @Test
            fun `should be equal to StringValue with same content`() {
                val cdataValue = CDATAValue("test content")
                val stringValue = StringValue("test content")

                assertThat(cdataValue).isEqualTo(stringValue)
            }

            @Test
            fun `should not be equal to StringValue with different content`() {
                val cdataValue = CDATAValue("content1")
                val stringValue = StringValue("content2")

                assertThat(cdataValue).isNotEqualTo(stringValue)
            }

            @Test
            fun `should be equal to StringValue when StringValue is compared to CDATAValue`() {
                val cdataValue = CDATAValue("test content")
                val stringValue = StringValue("test content")

                // Test symmetry - both directions should work
                assertThat(cdataValue).isEqualTo(stringValue)
                assertThat(stringValue).isEqualTo(cdataValue)
            }

            @Test
            fun `should not be equal to different type`() {
                val cdataValue = CDATAValue("test")
                val differentType = "test"

                assertThat(cdataValue).isNotEqualTo(differentType)
            }
        }

        @Nested
        @DisplayName("nodeToString() method")
        inner class NodeToStringMethod {
            @Test
            fun `should serialize CDATA with proper CDATA markup`() {
                val cdataValue = CDATAValue("test content")
                val result = cdataValue.nodeToString("", "\n")

                assertThat(result).isEqualTo("<![CDATA[test content]]>\n")
            }

            @Test
            fun `should escape closing sequence in CDATA content`() {
                val cdataValue = CDATAValue("data]]>more data")
                val result = cdataValue.nodeToString("", "\n")

                assertThat(result).isEqualTo("<![CDATA[data]]&gt;more data]]>\n")
            }

            @Test
            fun `should not escape other XML special characters`() {
                val cdataValue = CDATAValue("<tag>data</tag>&entity;'\"")
                val result = cdataValue.nodeToString("", "\n")

                assertThat(result).isEqualTo("<![CDATA[<tag>data</tag>&entity;'\"]]>\n")
            }
        }
    }

    @Nested
    @DisplayName("Integration with XML parsing")
    inner class XMLParsingIntegration {
        @Test
        fun `should parse CDATA from XML string`() {
            val xml = "<root><![CDATA[test content]]></root>"
            val xmlNode = toXMLNode(xml)

            assertThat(xmlNode.childNodes).hasSize(1)
            val child = xmlNode.childNodes[0]
            assertThat(child).isInstanceOf(CDATAValue::class.java)
            assertThat((child as CDATAValue).nativeValue).isEqualTo("test content")
        }
    }
}
