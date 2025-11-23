package io.specmatic.core.value

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import io.specmatic.core.pattern.ContractException
import io.specmatic.trimmedLinesList

internal class XMLNodeTest {
    @Test
    fun `parse XML`() {
        val node = toXMLNode("<data>data</data>")

        assertThat(node).isEqualTo(XMLNode("data", "data", emptyMap(), listOf(StringValue("data")), "", emptyMap()))
        assertThat(node.toStringLiteral()).isEqualTo("<data>data</data>")
    }

    @Test
    fun `parses the namespace prefix of a node`() {
        val node = toXMLNode("<ns:data>data</ns:data>")

        assertThat(node.namespacePrefix).isEqualTo("ns")
    }

    @Test
    fun `parses the namespaces declared in a node`() {
        val node = toXMLNode("<data xmlns:data=\"http://data\">data</data>")

        assertThat(node.namespaces).isEqualTo(mapOf("data" to "http://data"))
    }

    @Test
    fun `looks up a child node by name`() {
        val personDetailsXmlNode = toXMLNode("<data><person/></data>")

        val personNode = personDetailsXmlNode.findFirstChildByName("person")
        assertThat(personNode).isNotNull
    }

    @Test
    fun `returns null if there is no child node by that name`() {
        val personDetailsXmlNode = toXMLNode("<data><nobody/></data>")

        val personNode = personDetailsXmlNode.findFirstChildByName("person")
        assertThat(personNode).isNull()
    }

    @Test
    fun `path lookup looks up a child node by name`() {
        val personDetailsXmlNode = toXMLNode("<data><person/></data>")

        val personNode = personDetailsXmlNode.findFirstChildByPath("person")
        assertThat(personNode).isNotNull
    }

    @Test
    fun `path lookup returns null if there is no child node by the given name`() {
        val personDetailsXmlNode = toXMLNode("<data><nobody/></data>")

        val personNode = personDetailsXmlNode.findFirstChildByPath("person")
        assertThat(personNode).isNull()
    }

    @Test
    fun `looks up a node by path`() {
        val dataNode = toXMLNode("<data><person><name>Jill</name></person></data>")

        val nameNode = dataNode.findFirstChildByPath("person.name")
        assertThat(nameNode).isNotNull
    }

    @Test
    fun `returns null if there is no node at the given path`() {
        val dataNode = toXMLNode("<data><person><name>Jill</name></person></data>")

        val nameNode = dataNode.findFirstChildByPath("person.nobody")
        assertThat(nameNode).isNull()
    }

    @Test
    fun `looks up only the first node by path`() {
        val personDetailsXmlNode = toXMLNode("<data><person><name>Jack</name></person><person><name>Jill</name></person></data>")

        val nameNode = personDetailsXmlNode.findFirstChildByPath("person.name")
        val name = nameNode?.childNodes?.get(0)?.toStringLiteral()

        assertThat(name).isEqualTo("Jack")
    }

    @Test
    fun `resolves the namespace prefix of a node when provided in the same node and shows it in qname`() {
        val xmlNode = toXMLNode("<ns:data xmlns:ns=\"http://example.com/namespace/url\" />")

        assertThat(xmlNode.qname).isEqualTo("{http://example.com/namespace/url}data")
    }

    @Test
    fun `qname uses xmlns attribute if provided`() {
        val xmlNode = toXMLNode("<data xmlns=\"http://example.com/namespace/url\" />")

        assertThat(xmlNode.qname).isEqualTo("{http://example.com/namespace/url}data")
    }

    @Test
    fun `if no namespace exists, only the name is provided as qname`() {
        val xmlNode = toXMLNode("<data/>")

        assertThat(xmlNode.qname).isEqualTo("data")
    }

    @Test
    fun `if a namespace prefix can't be resolved, an exception is thrown when reading qname`() {
        val xmlNode = toXMLNode("<ns:data/>")

        assertThrows<ContractException> { xmlNode.qname }
    }

    @Test
    fun `an inherited namespace prefix gets resolved as the qname`() {
        val parentNode = toXMLNode("<parent xmlns:ns=\"http://example.com/namespace/url\"><ns:child /></parent>")
        val childNode = parentNode.findFirstChildByName("child")

        assertThat(childNode?.qname).isEqualTo("{http://example.com/namespace/url}child")
    }

    @Test
    fun `an XML node can create another nodes that inherits all its namespace`() {
        val initialNamespace = "http://data"
        val initialNode = toXMLNode("<data xmlns:data=\"$initialNamespace\">data</data>")
        val createdFromInitialNode = initialNode.createNewNode("newNode")

        assertThat(createdFromInitialNode.namespaces["data"]).isEqualTo(initialNamespace)
    }

    @Test
    fun `get namespace prefix from a name`() {
        assertThat("ns:name".namespacePrefix()).isEqualTo("ns")
    }

    @Test
    fun `get namespace prefix from a name with no prefix returns a blank string`() {
        assertThat("name".namespacePrefix()).isBlank
    }

    @Test
    fun `strip out the namespace prefix from a name`() {
        assertThat("ns:name".localName()).isEqualTo("name")
    }

    @Test
    fun `stripping out the namespace prefix from a name with no prefix returns the name`() {
        assertThat("name".localName()).isEqualTo("name")
    }

    @Test
    fun `resolves the namespace of a name in terms of known namespaces`() {
        val node = toXMLNode("<data xmlns:data=\"http://data\">data</data>")
        assertThat(node.resolveNamespace("data:stuff")).isEqualTo("http://data")
    }

    @Test
    fun `find first child by name`() {
        val node = toXMLNode("<person><name/></person>")
        assertThat(node.findFirstChildByName("name")).isInstanceOf(XMLNode::class.java)
    }

    @Test
    fun `return null if there is no child by the given name`() {
        val node = toXMLNode("<person><name/></person>")
        assertThat(node.findFirstChildByName("firstname")).isNull()
    }

    @Test
    fun `find first child by path`() {
        val node = toXMLNode("<person><name><firstname/></name></person>")
        assertThat(node.findFirstChildByPath("name.firstname")).isInstanceOf(XMLNode::class.java)
    }

    @Test
    fun `return null if there is no node at the given path`() {
        val node = toXMLNode("<person><name><firstname/></name></person>")
        assertThat(node.findFirstChildByPath("name.lastname")).isNull()
    }

    @Test
    fun `should find all child nodes by the given name`() {
        val node = toXMLNode("<person><name/><address/><address/></person>")
        assertThat(node.findChildrenByName("address")).hasSize(2)
    }

    @Test
    fun `should return an empty list if there is no child node by the given name`() {
        val node = toXMLNode("<person><name/></person>")
        assertThat(node.findChildrenByName("address")).isEmpty()
    }

    @Test
    fun `should return the namespace of the prefix in the given name`() {
        val node = toXMLNode("<person xmlns:ns0=\"http://ns\"><name/></person>")
        assertThat(node.resolveNamespace("ns0:address")).isEqualTo("http://ns")
    }

    @Test
    fun `should return an empty string if the given name has no prefix`() {
        val node = toXMLNode("<person xmlns:ns0=\"http://ns\"><name/></person>")
        assertThat(node.resolveNamespace("address")).isBlank
    }

    @Test
    fun `should throw an exception if the prefix in the given name is not recognised`() {
        val node = toXMLNode("<person xmlns:ns0=\"http://ns\"><name/></person>")
        assertThrows<ContractException> { node.resolveNamespace("ns1:address") }
    }

    @Test
    fun `namespace definitions are inherited 3 levels down`() {
        val expectedNamespaces = mapOf("ns1" to "http://one", "ns2" to "http://two", "ns3" to "http://three")

        val node = toXMLNode("""<level1 xmlns:ns1="http://one"><level2 xmlns:ns2="http://two"><level3 xmlns:ns3="http://three" /></level2></level1>""")
        val level3 = node.findFirstChildByPath("level2.level3")!!

        expectedNamespaces.forEach { (prefix, namespace) ->
            assertThat(level3.namespaces[prefix]).isEqualTo(namespace)
        }
    }

    @Test
    fun `serialize to pretty string value`() {
        val originalXml = toXMLNode("<customer><firstname>Jill</firstname><surname>Granger</surname></customer>")

        val prettyStringValue = originalXml.toPrettyStringValue()

        assertThat(prettyStringValue.trimmedLinesList()).isEqualTo("""
            <customer>
              <firstname>Jill</firstname>
              <surname>Granger</surname>
            </customer>""".trimIndent().trimmedLinesList())
    }

    @Test
    fun `serialize xml with an undeclared namespace prefix to pretty string value`() {
        val originalXml = xmlNode("ns0:customer") {
            xmlNode("firstname", mapOf("ns0:correct" to "true")) {
                text("Jill")
            }
            xmlNode("surname") {
                text("Granger")
            }
        }

        val prettyStringValue = originalXml.toPrettyStringValue()

        assertThat(prettyStringValue.trimmedLinesList()).isEqualTo("""
            <ns0:customer>
              <firstname ns0:correct="true">Jill</firstname>
              <surname>Granger</surname>
            </ns0:customer>""".trimIndent().trimmedLinesList())
    }

    @Test
    fun `xml node DSL should generate an xml node correctly`() {
        val parentNamespaces = mapOf("ns" to "http://namespace")

        val dslXML = xmlNode("ns0:customer", mapOf("ns0:special" to "true")) {
            parentNamespaces(parentNamespaces)
            xmlNode("firstname", mapOf("ns0:correct" to "true")) {
                text("Jill")
            }
            xmlNode("surname") {
                text("Granger")
            }
        }

        val expected = XMLNode("ns0:customer", mapOf("ns0:special" to StringValue("true")),
            childNodes = listOf(
                XMLNode("firstname", mapOf("ns0:correct" to StringValue("true")),
                    childNodes = listOf(
                        StringValue("Jill")
                    ),
                    parentNamespaces = parentNamespaces
                ),
                XMLNode("surname", emptyMap(),
                    childNodes = listOf(
                        StringValue("Granger")
                    ),
                    parentNamespaces = parentNamespaces
                ),
            ),
            parentNamespaces = parentNamespaces
        )

        assertThat(dslXML).isEqualTo(expected)
    }

    @Test
    fun `xml node should derive the fully qualified name for a value from its context`() {
        val targetNamespace = "http://localhost"
        val schema = xmlNode("schema", mapOf("targetNamespace" to targetNamespace)) {

        }

        val personNode = xmlNode("Person") {
            parentNamespaces(mapOf("ns0" to targetNamespace))

            xmlNode("name") {
                text("Jane Doe")
            }
        }.copy(schema = schema)

        val fqn = personNode.fullyQualifiedNameFromQName("ns0:helloworld")

        assertThat(fqn.namespace).isEqualTo(targetNamespace)
        assertThat(fqn.prefix).isEqualTo("ns0")
        assertThat(fqn.localName).isEqualTo("helloworld")
    }

    @Test
    fun `xml node should derive the fully qualified name for the value of its attribute from its context`() {
        val targetNamespace = "http://localhost"
        val schema = xmlNode("schema", mapOf("targetNamespace" to targetNamespace)) {

        }

        val personNode = xmlNode("Person", mapOf("type" to "ns0:helloworld")) {
            parentNamespaces(mapOf("ns0" to targetNamespace))

            xmlNode("name") {
                text("Jane Doe")
            }
        }.copy(schema = schema)

        val fqn = personNode.fullyQualifiedNameFromAttribute("type")

        assertThat(fqn.namespace).isEqualTo(targetNamespace)
        assertThat(fqn.prefix).isEqualTo("ns0")
        assertThat(fqn.localName).isEqualTo("helloworld")
    }

    @Nested
    @DisplayName("XML Node Attributes Handling Tests")
    inner class AttributesHandlingTests {
        @Test
        fun `should parse XML node with no attributes`() {
            val node = toXMLNode("<person></person>")
            assertThat(node.attributes).isEmpty()
        }

        @Test
        fun `should parse node with text content and no attributes`() {
            val node = toXMLNode("<person>John Doe</person>")

            assertThat(node.attributes).isEmpty()
            assertThat(node.childNodes).hasSize(1)
            assertThat(node.childNodes[0]).isEqualTo(StringValue("John Doe"))
        }

        @Test
        fun `should parse node with CDATA content and no attributes`() {
            val xml = "<description><![CDATA[Some <b>bold</b> text]]></description>"

            val node = toXMLNode(xml)

            assertThat(node.attributes).isEmpty()
            assertThat(node.childNodes).hasSize(1)
            assertThat(node.childNodes[0]).isInstanceOf(CDATAValue::class.java)
        }
    }
}
