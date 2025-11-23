package io.specmatic.core.value

import io.specmatic.core.NoBodyValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class ValueXMLAdjustmentTest {
    @Nested
    inner class StringValueAdjustmentTests {
        @Test
        fun `should set xml flag to true when adjusting StringValue`() {
            val stringValue = StringValue("test content")

            val adjusted = stringValue.adjustValueForXMLContentType()

            assertThat(adjusted).isInstanceOf(StringValue::class.java)
            val adjustedStringValue = adjusted as StringValue
            assertThat(adjustedStringValue.toStringLiteral()).isEqualTo("test content")
        }

        @Test
        fun `should escape special XML characters in StringValue`() {
            val stringValue = StringValue("<tag>")

            val adjusted = stringValue.adjustValueForXMLContentType() as StringValue

            assertThat(adjusted.toStringLiteral()).isEqualTo("&lt;tag&gt;")
        }
    }

    @Nested
    inner class XMLNodeAdjustmentTests {
        @Test
        fun `should adjust XMLNode with StringValue children`() {
            val xmlNode = XMLNode(
                name = "parent",
                realName = "parent",
                attributes = emptyMap(),
                childNodes = listOf(
                    StringValue("content with <tags>"),
                ),
                namespacePrefix = "",
                namespaces = emptyMap(),
            )

            val adjusted = xmlNode.adjustValueForXMLContentType()

            assertThat(adjusted).isInstanceOf(XMLNode::class.java)
            val adjustedNode = adjusted as XMLNode
            assertThat(adjustedNode.childNodes).hasSize(1)
            assertThat(adjustedNode.childNodes[0]).isInstanceOf(StringValue::class.java)
            val child = adjustedNode.childNodes[0] as StringValue
            assertThat(child.toStringLiteral()).contains("&lt;tags&gt;")
        }

        @Test
        fun `should recursively adjust nested XMLNode structures`() {
            val innerNode = XMLNode(
                name = "inner",
                realName = "inner",
                attributes = emptyMap(),
                childNodes = listOf(StringValue("inner & content")),
                namespacePrefix = "",
                namespaces = emptyMap()
            )
            val outerNode = XMLNode(
                name = "outer",
                realName = "outer",
                attributes = emptyMap(),
                childNodes = listOf(innerNode),
                namespacePrefix = "",
                namespaces = emptyMap()
            )

            val adjusted = outerNode.adjustValueForXMLContentType()

            assertThat(adjusted).isInstanceOf(XMLNode::class.java)
            val adjustedOuter = adjusted as XMLNode
            assertThat(adjustedOuter.childNodes).hasSize(1)
            assertThat(adjustedOuter.childNodes[0]).isInstanceOf(XMLNode::class.java)
            val adjustedInner = adjustedOuter.childNodes[0] as XMLNode
            assertThat(adjustedInner.childNodes).hasSize(1)
            val innerChild = adjustedInner.childNodes[0] as StringValue
            assertThat(innerChild.toStringLiteral()).contains("&amp;")
        }

        @Test
        fun `should not modify non-XML value`() {
            val jsonValue = JSONObjectValue(mapOf("key" to StringValue("value")))

            val adjusted = jsonValue.adjustValueForXMLContentType()

            assertThat(adjusted).isSameAs(jsonValue)
        }
    }
}
