package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.toXMLNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class XMLWildcardPatternTest {
    @Test
    fun `other namespace wildcard matches foreign namespace node`() {
        val wildcard = otherNamespaceWildcard()
        val node = toXMLNode("""<ext:item xmlns:ext="$FOREIGN_NAMESPACE"/>""")

        assertThat(wildcard.matches(node, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `other namespace wildcard rejects target namespace node`() {
        val wildcard = otherNamespaceWildcard()
        val node = toXMLNode("""<tns:item xmlns:tns="$TARGET_NAMESPACE"/>""")

        assertThat(wildcard.matches(node, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `other namespace wildcard rejects local namespace node`() {
        val wildcard = otherNamespaceWildcard()

        assertThat(wildcard.matches(toXMLNode("<item/>"), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `any namespace wildcard matches local target and foreign namespace nodes`() {
        val wildcard = XMLWildcardPattern(AnyXMLNamespace)

        assertThat(wildcard.matches(toXMLNode("<item/>"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(wildcard.matches(toXMLNode("""<tns:item xmlns:tns="$TARGET_NAMESPACE"/>"""), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(wildcard.matches(toXMLNode("""<ext:item xmlns:ext="$FOREIGN_NAMESPACE"/>"""), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `uri list wildcard only matches listed namespaces`() {
        val wildcard = XMLWildcardPattern(xmlNamespaceConstraint(FOREIGN_NAMESPACE, TARGET_NAMESPACE))

        assertThat(wildcard.matches(toXMLNode("""<ext:item xmlns:ext="$FOREIGN_NAMESPACE"/>"""), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(wildcard.matches(toXMLNode("""<other:item xmlns:other="urn:other"/>"""), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `optional unbounded wildcard consumes multiple matching nodes`() {
        val wildcard = XMLWildcardPattern(
            namespaceConstraint = xmlNamespaceConstraint("##other", TARGET_NAMESPACE),
            minOccurs = 0,
            maxOccurs = null,
            targetNamespace = TARGET_NAMESPACE
        )
        val nodes = listOf(
            toXMLNode("""<one:item xmlns:one="urn:one"/>"""),
            toXMLNode("""<two:item xmlns:two="urn:two"/>""")
        )

        val result = wildcard.matches(nodes, Resolver())

        assertThat(result.result).isInstanceOf(Result.Success::class.java)
        assertThat(result.remainder).isEmpty()
    }

    @Test
    fun `required wildcard fails on empty input`() {
        val wildcard = otherNamespaceWildcard()

        assertThat(wildcard.matches(emptyList(), Resolver()).result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `other namespace wildcard stops before following target namespace declared child`() {
        val parentPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                nodes = listOf(
                    XMLWildcardPattern(xmlNamespaceConstraint("##other", TARGET_NAMESPACE), minOccurs = 0, maxOccurs = null, targetNamespace = TARGET_NAMESPACE),
                    XMLPattern(XMLTypeData(name = "Known", realName = "tns:Known", nodes = listOf(StringPattern()), namespaceUri = TARGET_NAMESPACE))
                )
            )
        )
        val sample = toXMLNode(
            """
            <Parent xmlns:tns="$TARGET_NAMESPACE" xmlns:ext="$FOREIGN_NAMESPACE">
                <ext:Extension/>
                <tns:Known>value</tns:Known>
            </Parent>
            """.trimIndent()
        )

        assertThat(parentPattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `missing namespace constraint defaults to any namespace`() {
        val wildcard = XMLWildcardPattern(xmlNamespaceConstraint(null, TARGET_NAMESPACE))

        assertThat(wildcard.matches(toXMLNode("<item/>"), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(wildcard.matches(toXMLNode("""<tns:item xmlns:tns="$TARGET_NAMESPACE"/>"""), Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(wildcard.matches(toXMLNode("""<ext:item xmlns:ext="$FOREIGN_NAMESPACE"/>"""), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `optional wildcard generates no nodes`() {
        val wildcard = XMLWildcardPattern(AnyXMLNamespace, minOccurs = 0, maxOccurs = null)

        val generated = wildcard.generate(Resolver()) as XMLNode

        assertThat(generated.childNodes).isEmpty()
    }

    @Test
    fun `required other namespace wildcard generates a foreign namespace node`() {
        val wildcard = otherNamespaceWildcard()

        val generated = (wildcard.generate(Resolver()) as XMLNode).childNodes.single() as XMLNode

        assertThat(generated.elementNamespaceUri()).isNotEqualTo(TARGET_NAMESPACE)
    }

    @Test
    fun `any namespace wildcard encompasses other namespace wildcard`() {
        val result = XMLWildcardPattern(AnyXMLNamespace, minOccurs = 0, maxOccurs = null)
            .encompasses(otherNamespaceWildcard(), Resolver(), Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `other namespace wildcard does not encompass any namespace wildcard`() {
        val result = otherNamespaceWildcard()
            .encompasses(XMLWildcardPattern(AnyXMLNamespace), Resolver(), Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `other namespace wildcard encompasses concrete foreign namespace xml pattern`() {
        val concrete = XMLPattern(XMLTypeData(name = "item", realName = "ext:item", namespaceUri = FOREIGN_NAMESPACE))

        assertThat(otherNamespaceWildcard().encompasses(concrete, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `other namespace wildcard does not encompass concrete target namespace xml pattern`() {
        val concrete = XMLPattern(XMLTypeData(name = "item", realName = "tns:item", namespaceUri = TARGET_NAMESPACE))

        assertThat(otherNamespaceWildcard().encompasses(concrete, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `unbounded occurrence range encompasses required single occurrence`() {
        val wider = XMLWildcardPattern(AnyXMLNamespace, minOccurs = 0, maxOccurs = null)
        val narrower = XMLWildcardPattern(AnyXMLNamespace, minOccurs = 1, maxOccurs = 1)

        assertThat(wider.encompasses(narrower, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `single occurrence range does not encompass unbounded occurrence range`() {
        val narrower = XMLWildcardPattern(AnyXMLNamespace, minOccurs = 1, maxOccurs = 1)
        val wider = XMLWildcardPattern(AnyXMLNamespace, minOccurs = 0, maxOccurs = null)

        assertThat(narrower.encompasses(wider, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `parent xml pattern with wildcard encompasses compatible concrete child sequence`() {
        val wildcardParent = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                nodes = listOf(XMLWildcardPattern(xmlNamespaceConstraint("##other", TARGET_NAMESPACE), minOccurs = 0, maxOccurs = null, targetNamespace = TARGET_NAMESPACE))
            )
        )
        val concreteParent = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                nodes = listOf(
                    XMLPattern(XMLTypeData(name = "Extension", realName = "ext:Extension", namespaceUri = FOREIGN_NAMESPACE))
                )
            )
        )

        assertThat(wildcardParent.encompasses(concreteParent, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `xml pattern without attribute wildcard is not backward compatible with extra attributes`() {
        val thisPattern = XMLPattern(XMLTypeData(name = "Parent", realName = "Parent"))
        val otherPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributes = mapOf("tracking" to StringPattern())
            )
        )

        val result = thisPattern.encompasses(otherPattern, Resolver(), Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString())
            .contains("attribute \"tracking\"")
            .contains("has no anyAttribute wildcard that allows it")
    }

    @Test
    fun `xml pattern with any attribute wildcard is backward compatible with extra attributes`() {
        val thisPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(AnyXMLNamespace))
            )
        )
        val otherPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributes = mapOf("tracking" to StringPattern())
            )
        )

        assertThat(thisPattern.encompasses(otherPattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `other namespace attribute wildcard encompasses concrete foreign namespace attribute`() {
        val thisPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(xmlNamespaceConstraint("##other", TARGET_NAMESPACE)))
            )
        )
        val otherPattern = XMLPattern("""<Parent xmlns:ext="$FOREIGN_NAMESPACE" ext:tracking="(string)"/>""")

        assertThat(thisPattern.encompasses(otherPattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `other namespace attribute wildcard does not encompass concrete local attribute`() {
        val thisPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(xmlNamespaceConstraint("##other", TARGET_NAMESPACE)))
            )
        )
        val otherPattern = XMLPattern("""<Parent tracking="(string)"/>""")

        assertThat(thisPattern.encompasses(otherPattern, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `local namespace attribute wildcard does not encompass concrete foreign namespace attribute`() {
        val thisPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(xmlNamespaceConstraint("##local", TARGET_NAMESPACE)))
            )
        )
        val otherPattern = XMLPattern("""<Parent xmlns:ext="$FOREIGN_NAMESPACE" ext:tracking="(string)"/>""")

        assertThat(thisPattern.encompasses(otherPattern, Resolver(), Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `target namespace attribute wildcard encompasses concrete target namespace attribute`() {
        val thisPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(xmlNamespaceConstraint("##targetNamespace", TARGET_NAMESPACE)))
            )
        )
        val otherPattern = XMLPattern("""<Parent xmlns:tns="$TARGET_NAMESPACE" tns:tracking="(string)"/>""")

        assertThat(thisPattern.encompasses(otherPattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `attribute wildcard compatibility uses predeclared xml namespace`() {
        val thisPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(xmlNamespaceConstraint(XML_NAMESPACE, TARGET_NAMESPACE)))
            )
        )
        val otherPattern = XMLPattern("""<Parent xml:id="(string)"/>""")

        assertThat(thisPattern.encompasses(otherPattern, Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `xml pattern without attribute wildcard is not backward compatible with other attribute wildcard`() {
        val thisPattern = XMLPattern(XMLTypeData(name = "Parent", realName = "Parent"))
        val otherPattern = XMLPattern(
            XMLTypeData(
                name = "Parent",
                realName = "Parent",
                attributeWildcards = listOf(XMLAttributeWildcard(AnyXMLNamespace))
            )
        )

        val result = thisPattern.encompasses(otherPattern, Resolver(), Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString())
            .contains("wildcard ##any")
            .contains("has no compatible anyAttribute wildcard")
    }

    private fun otherNamespaceWildcard(): XMLWildcardPattern =
        XMLWildcardPattern(xmlNamespaceConstraint("##other", TARGET_NAMESPACE), targetNamespace = TARGET_NAMESPACE)

    companion object {
        private const val TARGET_NAMESPACE = "urn:target"
        private const val FOREIGN_NAMESPACE = "urn:foreign"
        private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    }
}
