package io.specmatic.core.wsdl.parser

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.XMLChoiceGroupPattern
import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.payload.RequestHeaders
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class WSDLWiringCharacterizationTest {
    @Test
    fun `choice child wiring preserves both alternatives in the parent type`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/choice_ref.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-ref", "SignonCustId")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("SignonCustIdType", emptyMap(), emptySet())
        val rootNode = typeInfo.nodes.single() as XMLNode
        val rootPattern = typeInfo.types.getValue("SignonCustIdType")

        assertThat(rootNode.name).isEqualTo("SignonCustId")
        assertThat(rootNode.attributes[TYPE_ATTRIBUTE_NAME]?.toStringLiteral()).isEqualTo("SignonCustIdType")
        assertThat(rootPattern).isInstanceOf(AnyPattern::class.java)
        rootPattern as AnyPattern
        assertThat(rootPattern.pattern).hasSize(2)
        assertThat(rootPattern.pattern).allSatisfy { pattern ->
            assertThat(pattern).isInstanceOf(XMLPattern::class.java)
        }
        assertThat(rootPattern.pattern.map { (it as XMLPattern).toPrettyString() }).anySatisfy {
            assertThat(it)
                .contains("<Choice-ref:SPName>(string)</Choice-ref:SPName>")
                .contains("<Choice-ref:CustId")
            assertThat(it).doesNotContain("<Choice-ref:CustLoginId>(string)</Choice-ref:CustLoginId>")
        }
        assertThat(rootPattern.pattern.map { (it as XMLPattern).toPrettyString() }).anySatisfy {
            assertThat(it)
                .contains("<Choice-ref:SPName>(string)</Choice-ref:SPName>")
                .contains("<Choice-ref:CustLoginId>(string)</Choice-ref:CustLoginId>")
            assertThat(it).doesNotContain("<Choice-ref:CustPermId>")
        }
    }

    @Test
    fun `single choice child wiring defaults to exactly one required occurrence`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar", "ScalarChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ScalarChoiceRequestType", emptyMap(), emptySet())
        val rootPattern = typeInfo.types.getValue("ScalarChoiceRequestType") as AnyPattern

        assertThat(rootPattern.pattern).hasSize(2)
        assertThat(rootPattern.pattern.map { (it as XMLPattern).toPrettyString() }).allSatisfy {
            assertThat(it).contains("<Choice-scalar:PrimaryName>(string)</Choice-scalar:PrimaryName>")
            assertThat(it.contains("<Choice-scalar:CustomerNumber>(string)</Choice-scalar:CustomerNumber>"))
                .isNotEqualTo(it.contains("<Choice-scalar:LoginId>(string)</Choice-scalar:LoginId>"))
        }
    }

    @Test
    fun `optional choice child wiring allows the whole choice group to be omitted`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/choice_optional.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-optional", "OptionalChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("OptionalChoiceRequestType", emptyMap(), emptySet())
        val rootPattern = typeInfo.types.getValue("OptionalChoiceRequestType") as AnyPattern

        assertThat(rootPattern.pattern).hasSize(2)
        assertThat(rootPattern.pattern.map { (it as XMLPattern).toPrettyString() }).anySatisfy {
            assertThat(it)
                .contains("<Choice-optional:SPName>(string)</Choice-optional:SPName>")
                .doesNotContain("CustLoginId")
        }
        assertThat(rootPattern.pattern.map { (it as XMLPattern).toPrettyString() }).anySatisfy {
            assertThat(it)
                .contains("<Choice-optional:SPName>(string)</Choice-optional:SPName>")
                .contains("<Choice-optional:CustLoginId>(string)</Choice-optional:CustLoginId>")
        }
    }

    @Test
    fun `repeating scalar choice child wiring preserves one alternative per choice occurrence combination`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice_repeating.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar-repeating", "RepeatingScalarChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RepeatingScalarChoiceRequestType", emptyMap(), emptySet())
        val resolver = Resolver(newPatterns = typeInfo.types)
        val rootPattern = typeInfo.types.getValue("RepeatingScalarChoiceRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()
        val variants = choiceGroup.newBasedOn(resolver).map { it as XMLChoiceGroupPattern }.toList()
        val generatedBodies = variants.map { XMLPattern(rootPattern.pattern.copy(nodes = listOf(rootPattern.pattern.nodes.first(), it))).generate(resolver).toStringLiteral() }

        assertThat(variants).hasSize(6)
        assertThat(generatedBodies).anySatisfy {
            assertThat(it).contains("PrimaryName>") 
            assertThat(countOccurrences(it, "<Choice-scalar-repeating:CustomerNumber>")).isEqualTo(1)
            assertThat(it).doesNotContain("LoginId>")
        }
        assertThat(generatedBodies).anySatisfy {
            assertThat(countOccurrences(it, "<Choice-scalar-repeating:LoginId>")).isEqualTo(1)
            assertThat(it).doesNotContain("CustomerNumber>")
        }
        assertThat(generatedBodies).anySatisfy {
            assertThat(countOccurrences(it, "<Choice-scalar-repeating:CustomerNumber>")).isEqualTo(2)
            assertThat(it).doesNotContain("LoginId>")
        }
        assertThat(generatedBodies).anySatisfy {
            assertThat(it).contains("CustomerNumber>").contains("LoginId>")
        }
        assertThat(generatedBodies).anySatisfy {
            assertThat(it).contains("LoginId>").contains("CustomerNumber>")
        }
        assertThat(generatedBodies).anySatisfy {
            assertThat(countOccurrences(it, "<Choice-scalar-repeating:LoginId>")).isEqualTo(2)
            assertThat(it).doesNotContain("CustomerNumber>")
        }
    }

    @Test
    fun `unbounded scalar choice child wiring preserves repeated choice-group semantics`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice_repeating_unbounded.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar-repeating-unbounded", "RepeatingScalarChoiceUnboundedRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RepeatingScalarChoiceUnboundedRequestType", emptyMap(), emptySet())
        val resolver = Resolver(newPatterns = typeInfo.types)
        val rootPattern = typeInfo.types.getValue("RepeatingScalarChoiceUnboundedRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()

        assertThat(choiceGroup.newBasedOn(resolver).toList()).hasSizeGreaterThan(2)
    }

    @Test
    fun `repeating complex choice child wiring preserves one alternative per choice occurrence combination`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/complex_choice_repeating.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-complex-repeating", "RepeatingComplexChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RepeatingComplexChoiceRequestType", emptyMap(), emptySet())
        val resolver = Resolver(newPatterns = typeInfo.types)
        val rootPattern = typeInfo.types.getValue("RepeatingComplexChoiceRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()
        val variants = choiceGroup.newBasedOn(resolver).map { it as XMLChoiceGroupPattern }.toList()
        val sequences = variants.map { variant ->
            variant.concreteSequence.orEmpty().map { occurrence ->
                ((occurrence.single() as XMLPattern).pattern.name).substringAfter(":")
            }
        }

        assertThat(variants).hasSize(6)
        assertThat(sequences).contains(listOf("CustomerByPermId"))
        assertThat(sequences).contains(listOf("CustomerByLogin"))
        assertThat(sequences).contains(listOf("CustomerByPermId", "CustomerByPermId"))
        assertThat(sequences).contains(listOf("CustomerByPermId", "CustomerByLogin"))
        assertThat(sequences).contains(listOf("CustomerByLogin", "CustomerByPermId"))
        assertThat(sequences).contains(listOf("CustomerByLogin", "CustomerByLogin"))
    }

    @Test
    fun `min occurs greater than one on choice wiring requires repeated occurrences`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice_repeating_min2.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar-repeating-min2", "RepeatingScalarChoiceMinTwoRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RepeatingScalarChoiceMinTwoRequestType", emptyMap(), emptySet())
        val resolver = Resolver(newPatterns = typeInfo.types)
        val rootPattern = typeInfo.types.getValue("RepeatingScalarChoiceMinTwoRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()
        val variants = choiceGroup.newBasedOn(resolver).map { it as XMLChoiceGroupPattern }.toList()
        val sequences = variants.map { variant ->
            variant.concreteSequence.orEmpty().map { occurrence ->
                ((occurrence.single() as XMLPattern).pattern.name).substringAfter(":")
            }
        }

        assertThat(variants).hasSize(4)
        assertThat(sequences).allSatisfy { assertThat(it).hasSize(2) }
        assertThat(sequences).containsExactlyInAnyOrder(
            listOf("CustomerNumber", "CustomerNumber"),
            listOf("CustomerNumber", "LoginId"),
            listOf("LoginId", "CustomerNumber"),
            listOf("LoginId", "LoginId"),
        )
    }

    private fun countOccurrences(text: String, token: String): Int {
        return text.windowed(token.length, 1).count { it == token }
    }

    @Test
    fun `getSOAPElement keeps simple primitive request payload wiring intact`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/hello.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("qr", "http://specmatic.io/SOAPService/", "SimpleRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("SimpleRequestType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "SimpleRequest", "SimpleRequestType", typeInfo)

        assertThat(typeInfo.nodes).containsExactly(toXMLNode("<SimpleRequest>(string)</SimpleRequest>"))
        assertThat(requestBody)
            .contains("<SimpleRequest>(string)</SimpleRequest>")
            .contains("<soapenv:Body>")
    }

    @Test
    fun `getSOAPElement keeps referred complex sequence payload wiring intact`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/order_api.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://www.example.com/orders", "CreateOrder")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("CreateOrderType", emptyMap(), emptySet())
        val rootNode = typeInfo.nodes.single() as XMLNode
        val requestBody = soapBody(soapElement, wsdl, "CreateOrder", "CreateOrderType", typeInfo)

        assertThat(rootNode.name).isEqualTo("CreateOrder")
        assertThat(rootNode.attributes[TYPE_ATTRIBUTE_NAME]?.toStringLiteral()).isEqualTo("CreateOrderType")
        assertThat((typeInfo.types.getValue("CreateOrderType") as XMLPattern).toPrettyString())
            .contains("<productid>(number)</productid>")
        assertThat(requestBody).contains("<CreateOrder specmatic_type=\"CreateOrderType\"/>")
    }

    @Test
    fun `simple type restrictions still collapse to simple payloads`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "Code")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("CodeType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "Code", "CodeType", typeInfo)

        assertThat(typeInfo.nodes).hasSize(1)
        assertThat((typeInfo.nodes.single() as XMLNode).name).isEqualTo("Code")
        assertThat(requestBody)
            .contains("http://example.com/wiring")
            .contains("(string)")
            .contains("Code")
    }

    @Test
    fun `token types in wsdl still generate simple string payloads`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "SessionToken")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("SessionTokenType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "SessionToken", "SessionTokenType", typeInfo)

        assertThat(typeInfo.nodes).containsExactly(toXMLNode("<Wiring:SessionToken>(string)</Wiring:SessionToken>"))
        assertThat(requestBody)
            .contains("SessionToken")
            .contains("(string)")
    }

    @Test
    fun `restricted token simple types still generate constrained string payloads`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "ConstrainedTimestamp")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ConstrainedTimestampType", emptyMap(), emptySet())
        val node = typeInfo.nodes.single() as XMLNode
        val xmlPattern = XMLPattern(node)
        val requestBody = soapBody(soapElement, wsdl, "ConstrainedTimestamp", "ConstrainedTimestampType", typeInfo)

        assertThat(node.toStringLiteral())
            .contains("maxLength 19")
            .contains("regex [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")
        assertThat(requestBody)
            .contains("ConstrainedTimestamp")
            .contains("maxLength 19")
        assertThat(xmlPattern.matches(node.copy(childNodes = listOf(StringValue("2026-03-13T10:11:12"))), Resolver()))
            .isInstanceOf(Result.Success::class.java)
        assertThat(xmlPattern.matches(node.copy(childNodes = listOf(StringValue("2026-03-13"))), Resolver()))
            .isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `ref child wiring still resolves the referenced node with qualification and optionality`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")
        val schema = wsdl.schemas.getValue("http://example.com/wiring")
        val addressType = schema.findByNodeNameAndAttribute("complexType", "name", "AddressType")
        val codeReference = addressType.findFirstChildByName("sequence")!!.findChildrenByName("element")[1]

        val (typeName, soapElement) = wsdl.getWSDLElementType("QualifiedAddress", codeReference).getWSDLElement()
        val typeInfo = soapElement.deriveSpecmaticTypes(typeName, emptyMap(), emptySet())
        val node = typeInfo.nodes.single() as XMLNode
        val requestBody = soapBody(soapElement, wsdl, node.realName, typeName, typeInfo)

        assertThat(typeName).isEqualTo("tns_Code")
        assertThat(node.name).isEqualTo("Code")
        assertThat(node.realName.localName()).isEqualTo("Code")
        assertThat(node.attributes[OCCURS_ATTRIBUTE_NAME]?.toStringLiteral()).isEqualTo(OPTIONAL_ATTRIBUTE_VALUE)
        assertThat(typeInfo.namespacePrefixes).hasSize(1)
        assertThat(requestBody)
            .contains("http://example.com/wiring")
            .contains("Code")
            .contains("specmatic_occurs=\"optional\"")
    }

    @Test
    fun `inline child wiring still derives the inline child payload shape`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")
        val schema = wsdl.schemas.getValue("http://example.com/wiring")
        val inlineCustomer = schema.findByNodeNameAndAttribute("element", "name", "InlineCustomer")
        val addressType = schema.findByNodeNameAndAttribute("complexType", "name", "AddressType")
        val inlineChild = addressType.findFirstChildByName("sequence")!!.findChildrenByName("element")[2]

        val (typeName, soapElement) = wsdl.getWSDLElementType("QualifiedAddress", inlineChild).getWSDLElement()
        val typeInfo = soapElement.deriveSpecmaticTypes(typeName, emptyMap(), emptySet())

        assertThat(typeName).isEqualTo("QualifiedAddress_inlineStatus")
        assertThat(typeInfo.nodes.single().toStringLiteral())
            .contains("inlineStatus")
            .contains("QualifiedAddress_inlineStatus")
        assertThat((typeInfo.types.getValue("QualifiedAddress_inlineStatus") as XMLPattern).toPrettyString())
            .contains("level")
            .contains("(number)")
    }

    @Test
    fun `all child wiring still preserves unqualified nillable children`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/stockquote.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("xsd1", "http://example.com/stockquote.xsd", "TradePriceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("TradePriceRequestType", emptyMap(), emptySet())
        val rootNode = typeInfo.nodes.single() as XMLNode

        assertThat(rootNode.name).isEqualTo("TradePriceRequest")
        assertThat(rootNode.attributes[TYPE_ATTRIBUTE_NAME]?.toStringLiteral()).isEqualTo("TradePriceRequestType")
        assertThat((typeInfo.types.getValue("TradePriceRequestType") as XMLPattern).toPrettyString())
            .contains("<tickerSymbol specmatic_nillable=\"true\">(string)</tickerSymbol>")
        assertThat(typeInfo.namespacePrefixes).isEmpty()
    }

    @Test
    fun `complex content extension wiring still merges base and extension children`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "ExtendedElement")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ExtendedElementType", emptyMap(), emptySet())

        assertThat((typeInfo.types.getValue("ExtendedElementType") as XMLPattern).toPrettyString())
            .contains("baseField")
            .contains("(string)")
            .contains("extraField")
            .contains("(number)")
    }

    @Test
    fun `simple content extension wiring still resolves the base simple type into the assembled payload`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "SimpleExtensionElement")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("SimpleExtensionElementType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "SimpleExtensionElement", "SimpleExtensionElementType", typeInfo)

        assertThat((typeInfo.types.getValue("SimpleExtensionElementType") as XMLPattern).toPrettyString())
            .contains("IdentifierType")
            .contains("(string)")
        assertThat(requestBody).contains("<SimpleExtensionElement specmatic_type=\"SimpleExtensionElementType\"/>")
    }

    @Test
    fun `simple content extension wiring supports primitive base types`() {
        val namespace = "http://example.com/simple-content-primitive"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentPrimitive"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="NumberType">
                                <xsd:simpleContent>
                                    <xsd:extension base="xsd:integer"/>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="Number" type="tns:NumberType"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-primitive.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Number"))
        val typeInfo = soapElement.deriveSpecmaticTypes("NumberType", emptyMap(), emptySet())

        assertThat((typeInfo.types.getValue("NumberType") as XMLPattern).toPrettyString())
            .contains("<SPECMATIC_TYPE>(number)</SPECMATIC_TYPE>")
    }

    @Test
    fun `imported schema wiring still resolves downstream children from imported xsd files`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/imported_types.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/imported-wsdl", "ImportedPerson")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ImportedPersonType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "ImportedPerson", "ImportedPersonType", typeInfo)

        assertThat((typeInfo.types.getValue("ImportedPersonType") as XMLPattern).toPrettyString())
            .contains("<name>(string)</name>")
        assertThat(requestBody).contains("<ImportedPerson specmatic_type=\"ImportedPersonType\"/>")
    }

    @Test
    fun `attribute group wiring expands direct and nested groups into complex type attributes`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/attribute_group.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/attribute-group", "Person")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("PersonType", emptyMap(), emptySet())
        val personPattern = typeInfo.types.getValue("PersonType") as XMLPattern

        assertXmlAttribute(personPattern, "traceId", "(string)")
        assertXmlAttribute(personPattern, "createdBy.opt", "(string)")
        assertXmlAttribute(personPattern, "requestNumber.opt", "(number)")
    }

    @Test
    fun `attribute group wiring expands groups on nested complex children`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/attribute_group.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/attribute-group", "Account")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("AccountType", emptyMap(), emptySet())
        val ownerPattern = typeInfo.types.getValue("AccountType_Owner") as XMLPattern

        assertXmlAttribute(ownerPattern, "traceId", "(string)")
        assertXmlAttribute(ownerPattern, "createdBy.opt", "(string)")
        assertXmlAttribute(ownerPattern, "requestNumber.opt", "(number)")
    }

    @Test
    fun `attribute group wiring resolves groups from imported schemas`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/attribute_group_import.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/attribute-group-import", "ImportedPerson")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ImportedPersonType", emptyMap(), emptySet())
        val importedPersonPattern = typeInfo.types.getValue("ImportedPersonType") as XMLPattern

        assertXmlAttribute(importedPersonPattern, "externalTrace", "(string)")
        assertXmlAttribute(importedPersonPattern, "externalTenant.opt", "(string)")
    }

    @Test
    fun `attribute group wiring rejects duplicate expanded attribute names`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/attribute_group.wsdl")
        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/attribute-group", "DuplicatePerson")
        )

        assertThatThrownBy {
            soapElement.deriveSpecmaticTypes("DuplicatePersonType", emptyMap(), emptySet())
        }.hasMessageContaining("Duplicate attribute traceId")
    }

    @Test
    fun `attribute group wiring ignores recursive groups`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/attribute_group.wsdl")
        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/attribute-group", "RecursivePerson")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RecursivePersonType", emptyMap(), emptySet())
        val recursivePersonPattern = typeInfo.types.getValue("RecursivePersonType") as XMLPattern

        assertXmlAttribute(recursivePersonPattern, "safe.opt", "(string)")
    }

    @Test
    fun `required untyped attribute defaults to string and ignores schema id metadata`() {
        val personPattern = personPatternForAttribute("""<xsd:attribute id="ID" name="id" use="required"/>""")

        assertXmlAttribute(personPattern, "id", "(string)")
        assertThat(personPattern.pattern.attributes).doesNotContainKey("ID")
    }

    @Test
    fun `optional untyped attribute defaults to optional string`() {
        val personPattern = personPatternForAttribute("""<xsd:attribute name="id"/>""")

        assertXmlAttribute(personPattern, "id.opt", "(string)")
    }

    @Test
    fun `explicit anySimpleType attribute maps to string`() {
        val personPattern = personPatternForAttribute("""<xsd:attribute name="value" type="xsd:anySimpleType" use="required"/>""")

        assertXmlAttribute(personPattern, "value", "(string)")
    }

    @Test
    fun `inline simpleType attribute resolves restriction base`() {
        val personPattern = personPatternForAttribute(
            """
            <xsd:attribute name="name" use="required">
                <xsd:simpleType>
                    <xsd:restriction base="xsd:string">
                        <xsd:enumeration value="FS-XML"/>
                    </xsd:restriction>
                </xsd:simpleType>
            </xsd:attribute>
            """.trimIndent()
        )

        assertXmlAttribute(personPattern, "name", "(string)")
    }

    @Test
    fun `referenced simpleType attribute resolves restriction base`() {
        val personPattern = personPatternForAttribute(
            """<xsd:attribute name="name" type="tns:name" use="required"/>""",
            """
            <xsd:simpleType name="name">
                <xsd:restriction base="xsd:string">
                    <xsd:enumeration value="FS-XML"/>
                </xsd:restriction>
            </xsd:simpleType>
            """.trimIndent()
        )

        assertXmlAttribute(personPattern, "name", "(string)")
    }

    @Test
    fun `referenced numeric simpleType attribute resolves restriction base`() {
        val personPattern = personPatternForAttribute(
            """<xsd:attribute name="count" type="tns:PositiveCount" use="required"/>""",
            """
            <xsd:simpleType name="PositiveCount">
                <xsd:restriction base="xsd:positiveInteger"/>
            </xsd:simpleType>
            """.trimIndent()
        )

        assertXmlAttribute(personPattern, "count", "(number) minimum 1)")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "normalizedString",
            "language",
            "Name",
            "NCName",
            "ID",
            "IDREF",
            "IDREFS",
            "ENTITY",
            "ENTITIES",
            "NMTOKEN",
            "NMTOKENS"
        ]
    )
    fun `xsd string derived attributes parse as strings`(xsdType: String) {
        val personPattern = personPatternForAttribute("""<xsd:attribute name="value" type="xsd:$xsdType" use="required"/>""")

        assertXmlAttribute(personPattern, "value", "(string)")
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "xsd:string|(string)",
            "xsd:int|(number)",
            "xsd:boolean|(boolean)"
        ],
        delimiter = '|'
    )
    fun `typed primitive attributes keep existing pattern mapping`(xsdType: String, expectedPattern: String) {
        val personPattern = personPatternForAttribute("""<xsd:attribute name="value" type="$xsdType" use="required"/>""")

        assertXmlAttribute(personPattern, "value", expectedPattern)
    }

    private fun loadWsdl(path: String): WSDL {
        val wsdlFile = File(path)
        return WSDL(toXMLNode(wsdlFile.readText()), wsdlFile.canonicalPath)
    }

    private fun personPatternForAttribute(attribute: String, simpleTypeDeclarations: String = ""): XMLPattern {
        val namespace = "http://example.com/attribute-types"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="AttributeTypes"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="PersonType">
                                <xsd:sequence>
                                    <xsd:element name="Name" type="xsd:string"/>
                                </xsd:sequence>
                                $attribute
                            </xsd:complexType>

                            <xsd:element name="Person" type="tns:PersonType"/>
                            $simpleTypeDeclarations
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/attribute-types.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Person"))
        val typeInfo = soapElement.deriveSpecmaticTypes("PersonType", emptyMap(), emptySet())
        return typeInfo.types.getValue("PersonType") as XMLPattern
    }

    private fun soapBody(
        soapElement: io.specmatic.core.wsdl.parser.message.WSDLElement,
        wsdl: WSDL,
        nodeName: String,
        specmaticTypeName: String,
        typeInfo: WSDLTypeInfo,
    ): String {
        val namespaces = wsdl.getNamespaces(typeInfo)
        return soapElement.getSOAPPayload(
            SOAPMessageType.Input,
            nodeName,
            specmaticTypeName,
            namespaces,
            typeInfo,
        ).specmaticStatement(RequestHeaders()).single()
    }

    private fun assertXmlAttribute(xmlPattern: XMLPattern, name: String, expectedPattern: String) {
        val attributePattern = xmlPattern.pattern.attributes[name]

        assertThat(attributePattern)
            .withFailMessage("Expected XML attribute $name in ${xmlPattern.pattern.attributes.keys}")
            .isInstanceOf(DeferredPattern::class.java)

        attributePattern as DeferredPattern
        assertThat(attributePattern.pattern).isEqualTo(expectedPattern)
    }
}
