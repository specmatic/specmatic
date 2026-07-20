package io.specmatic.core.wsdl.parser

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.pattern.DeferredPattern
import io.specmatic.core.pattern.XMLChoiceGroupPattern
import io.specmatic.core.pattern.XMLGenerationDecisions
import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLSequencePattern
import io.specmatic.core.pattern.XMLWildcardPattern
import io.specmatic.core.pattern.withPatternDelimiters
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
        assertThat(rootPattern).isInstanceOf(XMLPattern::class.java)
        rootPattern as XMLPattern
        assertThat(rootPattern.pattern.nodes.filterIsInstance<XMLPattern>().map { it.pattern.realName })
            .contains("Choice-ref:SPName")
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()
        assertThat(choiceGroup.choices).hasSize(2)
        assertThat(choiceGroup.choices).allSatisfy { choice ->
            assertThat(choice).allSatisfy { pattern ->
                assertThat(pattern).isInstanceOf(XMLPattern::class.java)
            }
        }
        assertThat(choiceNames(choiceGroup)).containsExactlyInAnyOrder(
            listOf("CustId"),
            listOf("CustLoginId")
        )
    }

    @Test
    fun `single choice child wiring defaults to exactly one required occurrence`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar", "ScalarChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ScalarChoiceRequestType", emptyMap(), emptySet())
        val rootPattern = typeInfo.types.getValue("ScalarChoiceRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()

        assertThat(choiceGroup.minOccurs).isEqualTo(1)
        assertThat(choiceGroup.maxOccurs).isEqualTo(1)
        assertThat(choiceGroup.choices).hasSize(2)
        assertThat(choiceNames(choiceGroup)).containsExactlyInAnyOrder(
            listOf("CustomerNumber"),
            listOf("LoginId")
        )
    }

    @Test
    fun `optional choice child wiring allows the whole choice group to be omitted`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/choice_optional.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-optional", "OptionalChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("OptionalChoiceRequestType", emptyMap(), emptySet())
        val rootPattern = typeInfo.types.getValue("OptionalChoiceRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()

        assertThat(choiceGroup.minOccurs).isEqualTo(0)
        assertThat(choiceGroup.maxOccurs).isEqualTo(1)
        assertThat(choiceGroup.choices).hasSize(1)
        assertThat(choiceGroup.matches(emptyList(), Resolver()).result).isInstanceOf(Result.Success::class.java)
        assertThat(choiceNames(choiceGroup)).containsExactly(listOf("CustLoginId"))
    }

    @Test
    fun `sequential choices remain compact instead of expanding into Cartesian variants`() {
        val wsdl = WSDL(toXMLNode(sequentialChoiceWsdl()), "/path/to/sequential-choice.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://sequential-choice", "SequentialChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("SequentialChoiceRequestType", emptyMap(), emptySet())
        val rootPattern = typeInfo.types.getValue("SequentialChoiceRequestType")

        assertThat(rootPattern).isInstanceOf(XMLPattern::class.java)
        rootPattern as XMLPattern
        assertThat(rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>()).hasSize(2)
    }

    @Test
    fun `repeating scalar choice child wiring preserves one alternative per choice occurrence combination`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice_repeating.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar-repeating", "RepeatingScalarChoiceRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RepeatingScalarChoiceRequestType", emptyMap(), emptySet())
        val resolver = Resolver(newPatterns = typeInfo.types.mapKeys { (typeName, _) -> withPatternDelimiters(typeName) })
        val rootPattern = typeInfo.types.getValue("RepeatingScalarChoiceRequestType") as XMLPattern
        val choiceGroup = rootPattern.pattern.nodes.filterIsInstance<XMLChoiceGroupPattern>().single()
        val variants = choiceGroup.newBasedOn(resolver).map { it as XMLSequencePattern }.toList()
        val generatedBodies = variants.map { XMLPattern(rootPattern.pattern.copy(nodes = listOf(rootPattern.pattern.nodes.first(), it))).generate(resolver).toStringLiteral() }

        assertThat(variants).hasSize(3)
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
            assertThat(it).contains("CustomerNumber>").contains("LoginId>")
        }
    }

    @Test
    fun `unbounded scalar choice child wiring preserves repeated choice-group semantics`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/state_machine/scalar_choice_repeating_unbounded.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://choice-scalar-repeating-unbounded", "RepeatingScalarChoiceUnboundedRequest")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("RepeatingScalarChoiceUnboundedRequestType", emptyMap(), emptySet())
        val resolver = Resolver(newPatterns = typeInfo.types.mapKeys { (typeName, _) -> withPatternDelimiters(typeName) })
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
        val variants = choiceGroup.newBasedOn(resolver).map { it as XMLSequencePattern }.toList()
        val sequences = variants.map(::choiceNames)

        assertThat(variants).hasSize(3)
        assertThat(sequences).containsExactlyInAnyOrder(
            listOf("CustomerByPermId"),
            listOf("CustomerByLogin"),
            listOf("CustomerByPermId", "CustomerByLogin")
        )
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
        val variants = choiceGroup.newBasedOn(resolver).map { it as XMLSequencePattern }.toList()
        val sequences = variants.map(::choiceNames)

        assertThat(variants).hasSize(1)
        assertThat(sequences).allSatisfy { assertThat(it).hasSize(2) }
        assertThat(sequences).containsExactlyInAnyOrder(
            listOf("CustomerNumber", "LoginId"),
        )
    }

    private fun countOccurrences(text: String, token: String): Int {
        return text.windowed(token.length, 1).count { it == token }
    }

    private fun choiceNames(sequence: XMLSequencePattern): List<String> =
        sequence.members.map { pattern -> (pattern as XMLPattern).pattern.realName.substringAfter(":") }

    private fun choiceNames(choiceGroup: XMLChoiceGroupPattern): List<List<String>> =
        choiceGroup.choices.map { choice ->
            choice.map { pattern -> (pattern as XMLPattern).pattern.realName.substringAfter(":") }
        }

    private object IncludeOptionalXMLNodes : XMLGenerationDecisions {
        override fun includeOptionalXMLNode(): Boolean = true
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
            .contains("<SimpleRequest>")
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
        assertThat(requestBody)
            .contains("<CreateOrder>")
            .contains("<productid>")
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
        assertThat(requestBody).contains("SessionToken")
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
        assertThat(requestBody).contains("ConstrainedTimestamp")
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
        val inlineStatusPattern = concreteRoot(
            typeInfo.types.getValue("QualifiedAddress_inlineStatus") as XMLPattern,
            "inlineStatus",
            "Wiring:inlineStatus",
            "http://example.com/wiring",
        )
        assertThat(inlineStatusPattern.matches(
            toXMLNode("""<Wiring:inlineStatus xmlns:Wiring="http://example.com/wiring"><Wiring:level>10</Wiring:level></Wiring:inlineStatus>"""),
            Resolver(),
        )).isInstanceOf(Result.Success::class.java)
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
        val tradePriceRequestPattern = concreteRoot(
            typeInfo.types.getValue("TradePriceRequestType") as XMLPattern,
            "TradePriceRequest",
            "TradePriceRequest",
            "http://example.com/stockquote.xsd",
        )
        assertThat(tradePriceRequestPattern.matches(
            toXMLNode("<TradePriceRequest><tickerSymbol/></TradePriceRequest>"),
            Resolver(),
        )).isInstanceOf(Result.Success::class.java)
        assertThat(typeInfo.namespacePrefixes).isEmpty()
    }

    @Test
    fun `complex content extension wiring still merges base and extension children`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "ExtendedElement")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ExtendedElementType", emptyMap(), emptySet())

        val extendedPattern = concreteRoot(
            typeInfo.types.getValue("ExtendedElementType") as XMLPattern,
            "ExtendedElement",
            "Wiring:ExtendedElement",
            "http://example.com/wiring",
        )
        assertThat(extendedPattern.matches(
            toXMLNode(
                """<Wiring:ExtendedElement xmlns:Wiring="http://example.com/wiring"><Wiring:baseField>value</Wiring:baseField><Wiring:extraField>10</Wiring:extraField></Wiring:ExtendedElement>"""
            ),
            Resolver(),
        )).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `recursive complex type child is retained and matches nested occurrences`() {
        val namespace = "http://example.com/recursive-complex-type"
        val wsdl = WSDL(toXMLNode(recursiveComplexTypeWsdl(namespace)), "/path/to/recursive-complex-type.wsdl")
        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Root"))
        val typeInfo = soapElement.deriveSpecmaticTypes("RootType", emptyMap(), emptySet())
        val rootPattern = typeInfo.members.single() as XMLPattern
        val resolver = Resolver(newPatterns = typeInfo.types.mapKeys { (typeName, _) -> withPatternDelimiters(typeName) })
        val nestedRecursiveValue = toXMLNode(
            """
            <tns:Root xmlns:tns="$namespace">
                <tns:child>
                    <tns:child/>
                </tns:child>
            </tns:Root>
            """.trimIndent()
        )

        assertThat(rootPattern.matches(nestedRecursiveValue, resolver)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `recursive optional repeating complex type generation terminates`() {
        val namespace = "http://example.com/recursive-complex-type"
        val wsdl = WSDL(toXMLNode(recursiveComplexTypeWsdl(namespace)), "/path/to/recursive-complex-type.wsdl")
        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Root"))
        val typeInfo = soapElement.deriveSpecmaticTypes("RootType", emptyMap(), emptySet())
        val rootPattern = typeInfo.members.single() as XMLPattern
        val resolver = Resolver(newPatterns = typeInfo.types.mapKeys { (typeName, _) -> withPatternDelimiters(typeName) })

        val generated = rootPattern.generate(resolver).toStringLiteral()

        assertThat(generated).contains(":Root").contains(":child")
    }

    @Test
    fun `recursive complex type generation terminates when the same type is reached through different element names`() {
        val rootPattern = XMLPattern(toXMLNode("""<tns:Root specmatic_type="A"/>"""))
        val typeA = XMLPattern(toXMLNode("""<tns:A><tns:b specmatic_type="B" specmatic_occurs="optional"/></tns:A>"""))
        val typeB = XMLPattern(toXMLNode("""<tns:B><tns:a specmatic_type="A" specmatic_occurs="optional"/></tns:B>"""))
        val resolver = Resolver(
            newPatterns = mapOf(
                "(A)" to typeA,
                "(B)" to typeB,
            )
        )

        val generated = rootPattern.generateXML(resolver, IncludeOptionalXMLNodes).toStringLiteral()

        assertThat(generated).contains(":Root").contains(":b")
        assertThat(countOccurrences(generated, "<tns:a>")).isLessThan(3)
    }

    @Test
    fun `inline complex content extension inherits base attributes and keeps extension children`() {
        val namespace = "http://example.com/cba-style-extension"
        val wsdl = WSDL(toXMLNode(cbaStyleInlineExtensionWsdl(namespace)), "/path/to/cba-style-extension.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Command"))
        val typeInfo = soapElement.deriveSpecmaticTypes("CommandType", emptyMap(), emptySet())
        val requestPattern = typeInfo.types.getValue("CommandType_retrieveCardStatusRequest") as XMLPattern

        assertXmlAttribute(requestPattern, "id", "(string)")
        assertThat(requestPattern.pattern.nodes.filterIsInstance<XMLPattern>().map { it.pattern.name })
            .contains("productAccessArrangement")
    }

    @Test
    fun `complex content extension attributes are inherited through chained bases`() {
        val namespace = "http://example.com/chained-extension-attributes"
        val wsdl = WSDL(toXMLNode(chainedExtensionAttributesWsdl(namespace)), "/path/to/chained-extension-attributes.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Final"))
        val typeInfo = soapElement.deriveSpecmaticTypes("FinalType", emptyMap(), emptySet())
        val finalPattern = typeInfo.types.getValue("FinalType") as XMLPattern

        assertXmlAttribute(finalPattern, "baseId", "(string)")
        assertXmlAttribute(finalPattern, "middleId.opt", "(string)")
        assertXmlAttribute(finalPattern, "finalId.opt", "(string)")
    }

    @Test
    fun `complex content extension inherits attribute groups and anyAttribute wildcards`() {
        val namespace = "http://example.com/extension-attribute-groups"
        val wsdl = WSDL(toXMLNode(extensionAttributeGroupWsdl(namespace)), "/path/to/extension-attribute-groups.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Derived"))
        val typeInfo = soapElement.deriveSpecmaticTypes("DerivedType", emptyMap(), emptySet())
        val derivedPattern = typeInfo.types.getValue("DerivedType") as XMLPattern

        assertXmlAttribute(derivedPattern, "traceId.opt", "(string)")
        assertThat(derivedPattern.pattern.attributeWildcards).hasSize(1)
    }

    @Test
    fun `complex content restriction inherits base attributes and anyAttribute wildcards`() {
        val namespace = "http://example.com/complex-content-restriction-attributes"
        val wsdl = WSDL(toXMLNode(complexContentRestrictionAttributeWsdl(namespace)), "/path/to/complex-content-restriction-attributes.wsdl")
        val derivedElement = toXMLNode("""<xsd:element xmlns:tns="$namespace" type="tns:DerivedType"/>""")
        val derivedType = wsdl.getComplexTypeNode(derivedElement)

        assertThat(derivedType.getAttributes().map { it.nameWithOptionality })
            .contains("traceId.opt", "localCode.opt")
        assertThat(derivedType.getAttributeWildcards()).hasSize(1)
    }

    @Test
    fun `complex content restriction derives restricted children with inherited attributes`() {
        val namespace = "http://example.com/complex-content-restriction-attributes"
        val wsdl = WSDL(toXMLNode(complexContentRestrictionAttributeWsdl(namespace)), "/path/to/complex-content-restriction-attributes.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Derived"))
        val typeInfo = soapElement.deriveSpecmaticTypes("DerivedType", emptyMap(), emptySet())
        val derivedPattern = typeInfo.types.getValue("DerivedType") as XMLPattern
        val childNames = derivedPattern.pattern.nodes.filterIsInstance<XMLPattern>().map { it.pattern.name }

        assertThat(childNames).containsExactly("id")
        assertXmlAttribute(derivedPattern, "traceId.opt", "(string)")
        assertXmlAttribute(derivedPattern, "localCode.opt", "(string)")
    }

    @Test
    fun `complex content extension attributes are emitted in SOAP payload templates`() {
        val namespace = "http://example.com/chained-extension-attributes"
        val wsdl = WSDL(toXMLNode(chainedExtensionAttributesWsdl(namespace)), "/path/to/chained-extension-attributes.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Final"))
        val typeInfo = soapElement.deriveSpecmaticTypes("FinalType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "Final", "FinalType", typeInfo)

        assertThat(requestBody)
            .contains("baseId=")
            .contains("middleId=")
            .contains("finalId=")
    }

    @Test
    fun `simple content extension wiring still resolves the base simple type into the assembled payload`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/wiring_routes.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/wiring", "SimpleExtensionElement")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("SimpleExtensionElementType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "SimpleExtensionElement", "SimpleExtensionElementType", typeInfo)

        assertThat(requestBody).contains("<SimpleExtensionElement>")
    }

    @Test
    fun `simple content extension wiring includes extension attribute groups`() {
        val namespace = "http://example.com/simple-content-extension-attributes"
        val wsdl = WSDL(toXMLNode(simpleContentExtensionAttributesWsdl(namespace)), "/path/to/simple-content-extension-attributes.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Code"))
        val typeInfo = soapElement.deriveSpecmaticTypes("CodeType", emptyMap(), emptySet())
        val codePattern = typeInfo.types.getValue("CodeType") as XMLPattern

        assertXmlAttribute(codePattern, "code.opt", "(string)")
    }

    @Test
    fun `simple content extension with named base matches direct text and extension attributes`() {
        val namespace = "http://example.com/simple-content-extension-attributes"
        val wsdl = WSDL(toXMLNode(simpleContentExtensionAttributesWsdl(namespace)), "/path/to/simple-content-extension-attributes.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Code"))
        val typeInfo = soapElement.deriveSpecmaticTypes("CodeType", emptyMap(), emptySet())
        val codePattern = concreteRoot(typeInfo.types.getValue("CodeType") as XMLPattern, "Code", "tns:Code", namespace)
        val sample = toXMLNode("""<tns:Code xmlns:tns="$namespace" code="ABC">item</tns:Code>""")

        assertThat(codePattern.matches(sample, Resolver()))
            .isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `simple content extension with named base resolves simple type chains`() {
        val namespace = "http://example.com/simple-content-extension-chain"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentExtensionChain"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:simpleType name="CodeText">
                                <xsd:restriction base="xsd:token">
                                    <xsd:minLength value="2"/>
                                </xsd:restriction>
                            </xsd:simpleType>

                            <xsd:simpleType name="CodeAlias">
                                <xsd:restriction base="tns:CodeText">
                                    <xsd:maxLength value="8"/>
                                    <xsd:pattern value="[A-Z]+"/>
                                </xsd:restriction>
                            </xsd:simpleType>

                            <xsd:attributeGroup name="CodeAttributes">
                                <xsd:attribute name="code" type="xsd:string"/>
                            </xsd:attributeGroup>

                            <xsd:complexType name="CodeType">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:CodeAlias">
                                        <xsd:attributeGroup ref="tns:CodeAttributes"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="Code" type="tns:CodeType"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-extension-chain.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Code"))
        val typeInfo = soapElement.deriveSpecmaticTypes("CodeType", emptyMap(), emptySet())
        val codePattern = concreteRoot(typeInfo.types.getValue("CodeType") as XMLPattern, "Code", "tns:Code", namespace)
        val sample = toXMLNode("""<tns:Code xmlns:tns="$namespace" code="ABC">ABC</tns:Code>""")

        assertThat(codePattern.matches(sample, Resolver()))
            .isInstanceOf(Result.Success::class.java)
        assertThat(codePattern.matches(
            toXMLNode("""<tns:Code xmlns:tns="$namespace" code="ABC">a</tns:Code>"""),
            Resolver(),
        )).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `simple content extension inherits scalar value and attributes from complex simple content base`() {
        val namespace = "http://example.com/simple-content-complex-base"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentComplexBase"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:attributeGroup name="DateAttributes">
                                <xsd:attribute name="timezone" type="xsd:string"/>
                            </xsd:attributeGroup>

                            <xsd:complexType name="DateTime">
                                <xsd:simpleContent>
                                    <xsd:extension base="xsd:dateTime">
                                        <xsd:attributeGroup ref="tns:DateAttributes"/>
                                        <xsd:anyAttribute namespace="##any" processContents="skip"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:complexType name="LabelledDateTime">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:DateTime"/>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="LabelledDateTime" type="tns:LabelledDateTime"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-complex-base.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "LabelledDateTime"))
        val typeInfo = soapElement.deriveSpecmaticTypes("LabelledDateTime", emptyMap(), emptySet())
        val pattern = concreteRoot(typeInfo.types.getValue("LabelledDateTime") as XMLPattern, "LabelledDateTime", "tns:LabelledDateTime", namespace)

        assertThat(pattern.matches(
            toXMLNode("""<tns:LabelledDateTime xmlns:tns="$namespace">2026-07-19T10:15:30Z</tns:LabelledDateTime>"""),
            Resolver(),
        )).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(
            toXMLNode("""<tns:LabelledDateTime xmlns:tns="$namespace">not-a-date</tns:LabelledDateTime>"""),
            Resolver(),
        )).isInstanceOf(Result.Failure::class.java)
        assertXmlAttribute(pattern, "timezone.opt", "(string)")
        assertThat(pattern.pattern.attributeWildcards).hasSize(1)
    }

    @Test
    fun `simple content extension inherits attributes through complex simple content chains`() {
        val namespace = "http://example.com/simple-content-complex-chain"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentComplexChain"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="BaseText">
                                <xsd:simpleContent>
                                    <xsd:extension base="xsd:string">
                                        <xsd:attribute name="baseId" type="xsd:string"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:complexType name="MiddleText">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:BaseText">
                                        <xsd:attribute name="middleId" type="xsd:string"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:complexType name="FinalText">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:MiddleText">
                                        <xsd:attribute name="finalId" type="xsd:string"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="Final" type="tns:FinalText"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-complex-chain.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Final"))
        val typeInfo = soapElement.deriveSpecmaticTypes("FinalText", emptyMap(), emptySet())
        val pattern = concreteRoot(typeInfo.types.getValue("FinalText") as XMLPattern, "Final", "tns:Final", namespace)

        assertThat(pattern.matches(
            toXMLNode("""<tns:Final xmlns:tns="$namespace">value</tns:Final>"""),
            Resolver(),
        )).isInstanceOf(Result.Success::class.java)
        assertXmlAttribute(pattern, "baseId.opt", "(string)")
        assertXmlAttribute(pattern, "middleId.opt", "(string)")
        assertXmlAttribute(pattern, "finalId.opt", "(string)")
    }

    @Test
    fun `simple content restriction over complex simple content base resolves base scalar value`() {
        val namespace = "http://example.com/simple-content-complex-restriction-boolean"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentComplexRestrictionBoolean"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:attributeGroup name="BaseAttributes">
                                <xsd:attribute name="baseLabel" type="xsd:string"/>
                            </xsd:attributeGroup>

                            <xsd:complexType name="BooleanFull">
                                <xsd:simpleContent>
                                    <xsd:extension base="xsd:boolean">
                                        <xsd:attributeGroup ref="tns:BaseAttributes"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:complexType name="Boolean">
                                <xsd:simpleContent>
                                    <xsd:restriction base="tns:BooleanFull">
                                        <xsd:pattern value="true|false"/>
                                        <xsd:attribute name="source" type="xsd:string"/>
                                    </xsd:restriction>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="Boolean" type="tns:Boolean"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-complex-restriction-boolean.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Boolean"))
        val typeInfo = soapElement.deriveSpecmaticTypes("Boolean", emptyMap(), emptySet())
        val pattern = concreteRoot(typeInfo.types.getValue("Boolean") as XMLPattern, "Boolean", "tns:Boolean", namespace)
        val sample = toXMLNode("""<tns:Boolean xmlns:tns="$namespace" baseLabel="inherited" source="request">true</tns:Boolean>""")

        assertXmlAttribute(pattern, "baseLabel.opt", "(string)")
        assertXmlAttribute(pattern, "source.opt", "(string)")
        assertThat(pattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `simple content restriction over string complex simple content base keeps local facets and attributes`() {
        val namespace = "http://example.com/simple-content-complex-restriction-string"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentComplexRestrictionString"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="TextFull">
                                <xsd:simpleContent>
                                    <xsd:extension base="xsd:string">
                                        <xsd:attribute name="baseLabel" type="xsd:string"/>
                                    </xsd:extension>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:complexType name="Code">
                                <xsd:simpleContent>
                                    <xsd:restriction base="tns:TextFull">
                                        <xsd:minLength value="2"/>
                                        <xsd:maxLength value="8"/>
                                        <xsd:pattern value="[A-Z]+"/>
                                        <xsd:attribute name="code" type="xsd:string"/>
                                    </xsd:restriction>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="Code" type="tns:Code"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-complex-restriction-string.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Code"))
        val typeInfo = soapElement.deriveSpecmaticTypes("Code", emptyMap(), emptySet())
        val pattern = concreteRoot(typeInfo.types.getValue("Code") as XMLPattern, "Code", "tns:Code", namespace)
        val sample = toXMLNode("""<tns:Code xmlns:tns="$namespace" baseLabel="inherited" code="A1">ABC</tns:Code>""")

        assertXmlAttribute(pattern, "baseLabel.opt", "(string)")
        assertXmlAttribute(pattern, "code.opt", "(string)")
        assertThat(pattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
        assertThat(pattern.matches(
            toXMLNode("""<tns:Code xmlns:tns="$namespace">a</tns:Code>"""),
            Resolver(),
        )).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `recursive complex simple content extension fails clearly`() {
        val namespace = "http://example.com/simple-content-complex-recursive"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentComplexRecursive"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="A">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:B"/>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:complexType name="B">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:A"/>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="A" type="tns:A"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-complex-recursive.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "A"))

        assertThatThrownBy {
            soapElement.deriveSpecmaticTypes("A", emptyMap(), emptySet())
        }.hasMessageContaining("Recursive simple type/simple content base reference")
    }

    @Test
    fun `complex base without simple content extension fails clearly when used as simple content base`() {
        val namespace = "http://example.com/simple-content-complex-invalid-base"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentComplexInvalidBase"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="BaseObject">
                                <xsd:sequence>
                                    <xsd:element name="Name" type="xsd:string"/>
                                </xsd:sequence>
                            </xsd:complexType>

                            <xsd:complexType name="Scalar">
                                <xsd:simpleContent>
                                    <xsd:extension base="tns:BaseObject"/>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="Scalar" type="tns:Scalar"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-complex-invalid-base.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Scalar"))

        assertThatThrownBy {
            soapElement.deriveSpecmaticTypes("Scalar", emptyMap(), emptySet())
        }.hasMessageContaining("Complex type tns:BaseObject used as simpleContent base does not contain simpleContent/extension or simpleContent/restriction")
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

        val numberPattern = concreteRoot(
            typeInfo.types.getValue("NumberType") as XMLPattern,
            "Number",
            "tns:Number",
            namespace,
        )
        assertThat(numberPattern.matches(
            toXMLNode("""<tns:Number xmlns:tns="$namespace">10</tns:Number>"""),
            Resolver(),
        )).isInstanceOf(Result.Success::class.java)
        assertThat(numberPattern.matches(
            toXMLNode("""<tns:Number xmlns:tns="$namespace">not-a-number</tns:Number>"""),
            Resolver(),
        )).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `simple content extension with unsupported xsd primitive base falls back to string`() {
        val namespace = "http://example.com/simple-content-unsupported-primitive"
        val wsdl = WSDL(
            toXMLNode(
                """
                <definitions name="SimpleContentUnsupportedPrimitive"
                             targetNamespace="$namespace"
                             xmlns:tns="$namespace"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                             xmlns="http://schemas.xmlsoap.org/wsdl/">
                    <types>
                        <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                            <xsd:complexType name="UnsupportedPrimitiveType">
                                <xsd:simpleContent>
                                    <xsd:extension base="xsd:unknownPrimitive"/>
                                </xsd:simpleContent>
                            </xsd:complexType>

                            <xsd:element name="UnsupportedPrimitive" type="tns:UnsupportedPrimitiveType"/>
                        </xsd:schema>
                    </types>
                </definitions>
                """.trimIndent()
            ),
            "/path/to/simple-content-unsupported-primitive.wsdl"
        )

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "UnsupportedPrimitive"))
        val typeInfo = soapElement.deriveSpecmaticTypes("UnsupportedPrimitiveType", emptyMap(), emptySet())
        val pattern = concreteRoot(typeInfo.types.getValue("UnsupportedPrimitiveType") as XMLPattern, "UnsupportedPrimitive", "tns:UnsupportedPrimitive", namespace)
        val sample = toXMLNode("""<tns:UnsupportedPrimitive xmlns:tns="$namespace">value</tns:UnsupportedPrimitive>""")

        assertThat(pattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `imported schema wiring still resolves downstream children from imported xsd files`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/imported_types.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/imported-wsdl", "ImportedPerson")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ImportedPersonType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "ImportedPerson", "ImportedPersonType", typeInfo)

        assertThat(requestBody)
            .contains("<ImportedPerson>")
            .contains("<name>")
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
    fun `attribute ref resolves global attribute declaration`() {
        val personPattern = personPatternForAttribute(
            """<xsd:attribute ref="tns:traceId" use="required"/>""",
            """<xsd:attribute name="traceId" type="xsd:ID"/>"""
        )

        assertXmlAttribute(personPattern, "traceId", "(string)")
        assertThat(personPattern.pattern.attributeNamespaceUri("traceId")).isEqualTo("http://example.com/attribute-types")
    }

    @Test
    fun `attribute ref resolves predeclared xml namespace prefix`() {
        val namespace = "http://example.com/xml-attribute-ref"
        val wsdl = WSDL(toXMLNode(xmlAttributeReferenceWsdl(namespace)), "/path/to/xml-attribute-ref.wsdl")

        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Person"))
        val typeInfo = soapElement.deriveSpecmaticTypes("PersonType", emptyMap(), emptySet())
        val personPattern = typeInfo.types.getValue("PersonType") as XMLPattern

        assertXmlAttribute(personPattern, "id", "(string)")
        assertThat(personPattern.pattern.attributeNamespaceUri("id")).isEqualTo("http://www.w3.org/XML/1998/namespace")
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
    fun `referenced simpleType attribute resolves chained restriction base`() {
        val personPattern = personPatternForAttribute(
            """<xsd:attribute name="name" type="tns:DisplayName" use="required"/>""",
            """
            <xsd:simpleType name="NameText">
                <xsd:restriction base="xsd:token">
                    <xsd:minLength value="2"/>
                </xsd:restriction>
            </xsd:simpleType>

            <xsd:simpleType name="DisplayName">
                <xsd:restriction base="tns:NameText">
                    <xsd:maxLength value="10"/>
                </xsd:restriction>
            </xsd:simpleType>
            """.trimIndent()
        )

        assertXmlAttribute(personPattern, "name", "(string) minLength 2 maxLength 10)")
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

    @Test
    fun `xs any child wiring preserves optional unbounded foreign namespace wildcard`() {
        val namespace = "http://example.com/wildcard"
        val wsdl = WSDL(toXMLNode(wildcardWsdl(namespace)), "/path/to/wildcard.wsdl")
        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Header"))

        val typeInfo = soapElement.deriveSpecmaticTypes("HeaderType", emptyMap(), emptySet())
        val headerPattern = concreteRoot(typeInfo.types.getValue("HeaderType") as XMLPattern, "Header", "tns:Header", namespace)

        val wildcard = headerPattern.pattern.nodes.filterIsInstance<XMLWildcardPattern>().single()
        val sample = toXMLNode(
            """
            <tns:Header xmlns:tns="$namespace" xmlns:ext="urn:extension">
                <tns:Known>value</tns:Known>
                <ext:Extension>
                    <ext:Nested/>
                </ext:Extension>
            </tns:Header>
            """.trimIndent()
        )

        assertThat(wildcard.minOccurs).isEqualTo(0)
        assertThat(wildcard.maxOccurs).isNull()
        assertThat(headerPattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `xs any with other namespace rejects target namespace extension element`() {
        val namespace = "http://example.com/wildcard"
        val wsdl = WSDL(toXMLNode(wildcardWsdl(namespace)), "/path/to/wildcard.wsdl")
        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Header"))

        val typeInfo = soapElement.deriveSpecmaticTypes("HeaderType", emptyMap(), emptySet())
        val headerPattern = concreteRoot(typeInfo.types.getValue("HeaderType") as XMLPattern, "Header", "tns:Header", namespace)
        val sample = toXMLNode(
            """
            <tns:Header xmlns:tns="$namespace">
                <tns:Known>value</tns:Known>
                <tns:Extension/>
            </tns:Header>
            """.trimIndent()
        )

        assertThat(headerPattern.matches(sample, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `xs any without namespace attribute defaults to any namespace`() {
        val namespace = "http://example.com/wildcard"
        val wsdl = WSDL(toXMLNode(wildcardWsdl(namespace, anyNamespaceAttribute = "")), "/path/to/wildcard.wsdl")
        val soapElement = wsdl.getSOAPElement(FullyQualifiedName("tns", namespace, "Header"))

        val typeInfo = soapElement.deriveSpecmaticTypes("HeaderType", emptyMap(), emptySet())
        val headerPattern = concreteRoot(typeInfo.types.getValue("HeaderType") as XMLPattern, "Header", "tns:Header", namespace)
        val sample = toXMLNode(
            """
            <tns:Header xmlns:tns="$namespace">
                <tns:Known>value</tns:Known>
                <tns:Extension/>
            </tns:Header>
            """.trimIndent()
        )

        assertThat(headerPattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `xsd anyAttribute permits otherwise unexpected attributes`() {
        val namespace = "http://example.com/attribute-types"
        val personPattern = concreteRoot(
            personPatternForAttribute("""<xsd:anyAttribute/>"""),
            "Person",
            "tns:Person",
            namespace
        )
        val sample = toXMLNode(
            """
            <tns:Person xmlns:tns="$namespace" tracking="abc">
                <tns:Name>Jane</tns:Name>
            </tns:Person>
            """.trimIndent()
        )

        assertThat(personPattern.pattern.attributeWildcards).hasSize(1)
        assertThat(personPattern.matches(sample, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `xml attributes remain strict without anyAttribute wildcard`() {
        val namespace = "http://example.com/attribute-types"
        val personPattern = concreteRoot(
            personPatternForAttribute(""),
            "Person",
            "tns:Person",
            namespace
        )
        val sample = toXMLNode(
            """
            <tns:Person xmlns:tns="$namespace" tracking="abc">
                <tns:Name>Jane</tns:Name>
            </tns:Person>
            """.trimIndent()
        )

        assertThat(personPattern.matches(sample, Resolver())).isInstanceOf(Result.Failure::class.java)
    }

    private fun loadWsdl(path: String): WSDL {
        val wsdlFile = File(path)
        return WSDL(toXMLNode(wsdlFile.readText()), wsdlFile.canonicalPath)
    }

    private fun sequentialChoiceWsdl(): String =
        """
        <definitions name="SequentialChoiceService"
                     targetNamespace="http://sequential-choice"
                     xmlns:tns="http://sequential-choice"
                     xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="http://sequential-choice" elementFormDefault="qualified">
                    <xsd:element name="FirstA" type="xsd:string"/>
                    <xsd:element name="FirstB" type="xsd:string"/>
                    <xsd:element name="SecondA" type="xsd:string"/>
                    <xsd:element name="SecondB" type="xsd:string"/>
                    <xsd:element name="SequentialChoiceRequest">
                        <xsd:complexType>
                            <xsd:sequence>
                                <xsd:choice>
                                    <xsd:element ref="tns:FirstA"/>
                                    <xsd:element ref="tns:FirstB"/>
                                </xsd:choice>
                                <xsd:choice>
                                    <xsd:element ref="tns:SecondA"/>
                                    <xsd:element ref="tns:SecondB"/>
                                </xsd:choice>
                            </xsd:sequence>
                        </xsd:complexType>
                    </xsd:element>
                </xsd:schema>
            </types>
            <message name="SequentialChoiceRequestMessage">
                <part name="parameters" element="tns:SequentialChoiceRequest"/>
            </message>
            <message name="SequentialChoiceResponseMessage"/>
            <portType name="SequentialChoicePortType">
                <operation name="sequentialChoice">
                    <input message="tns:SequentialChoiceRequestMessage"/>
                    <output message="tns:SequentialChoiceResponseMessage"/>
                </operation>
            </portType>
            <binding name="SequentialChoiceBinding" type="tns:SequentialChoicePortType">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <operation name="sequentialChoice">
                    <soap:operation soapAction="/sequential-choice"/>
                    <input>
                        <soap:body use="literal"/>
                    </input>
                    <output>
                        <soap:body use="literal"/>
                    </output>
                </operation>
            </binding>
            <service name="SequentialChoiceService">
                <port name="SequentialChoicePort" binding="tns:SequentialChoiceBinding">
                    <soap:address location="http://localhost:9000/sequential-choice"/>
                </port>
            </service>
        </definitions>
        """.trimIndent()

    private fun concreteRoot(pattern: XMLPattern, name: String, realName: String, namespace: String): XMLPattern =
        pattern.copy(pattern = pattern.pattern.copy(name = name, realName = realName, namespaceUri = namespace))

    private fun wildcardWsdl(namespace: String, anyNamespaceAttribute: String = "namespace=\"##other\""): String =
        """
        <definitions name="Wildcard"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:complexType name="HeaderType">
                        <xsd:sequence>
                            <xsd:element name="Known" type="xsd:string"/>
                            <xsd:any $anyNamespaceAttribute minOccurs="0" maxOccurs="unbounded" processContents="skip"/>
                        </xsd:sequence>
                    </xsd:complexType>
                    <xsd:element name="Header" type="tns:HeaderType"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun cbaStyleInlineExtensionWsdl(namespace: String): String =
        """
        <definitions name="CBAStyleExtension"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:complexType name="CommandRequestBase">
                        <xsd:attribute id="ID" name="id" use="required"/>
                    </xsd:complexType>

                    <xsd:complexType name="CommandType">
                        <xsd:choice minOccurs="1" maxOccurs="unbounded">
                            <xsd:element minOccurs="0" maxOccurs="1" name="retrieveCardStatusRequest">
                                <xsd:complexType>
                                    <xsd:complexContent mixed="false">
                                        <xsd:extension base="tns:CommandRequestBase">
                                            <xsd:sequence minOccurs="0" maxOccurs="1">
                                                <xsd:element minOccurs="0" maxOccurs="1" name="productAccessArrangement" type="xsd:string"/>
                                            </xsd:sequence>
                                        </xsd:extension>
                                    </xsd:complexContent>
                                </xsd:complexType>
                            </xsd:element>
                        </xsd:choice>
                    </xsd:complexType>

                    <xsd:element name="Command" type="tns:CommandType"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun recursiveComplexTypeWsdl(namespace: String): String =
        """
        <definitions name="RecursiveComplexType"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:complexType name="Node">
                        <xsd:sequence>
                            <xsd:element minOccurs="0" maxOccurs="unbounded" name="child" type="tns:Node"/>
                            <xsd:element minOccurs="0" maxOccurs="1" name="label" type="xsd:string"/>
                        </xsd:sequence>
                    </xsd:complexType>

                    <xsd:element name="Root" type="tns:Node"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun chainedExtensionAttributesWsdl(namespace: String): String =
        """
        <definitions name="ChainedExtensionAttributes"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:complexType name="BaseType">
                        <xsd:attribute name="baseId" type="xsd:string" use="required"/>
                    </xsd:complexType>

                    <xsd:complexType name="MiddleType">
                        <xsd:complexContent>
                            <xsd:extension base="tns:BaseType">
                                <xsd:attribute name="middleId" type="xsd:string"/>
                            </xsd:extension>
                        </xsd:complexContent>
                    </xsd:complexType>

                    <xsd:complexType name="FinalType">
                        <xsd:complexContent>
                            <xsd:extension base="tns:MiddleType">
                                <xsd:attribute name="finalId" type="xsd:string"/>
                            </xsd:extension>
                        </xsd:complexContent>
                    </xsd:complexType>

                    <xsd:element name="Final" type="tns:FinalType"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun extensionAttributeGroupWsdl(namespace: String): String =
        """
        <definitions name="ExtensionAttributeGroups"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:attributeGroup name="TraceAttributes">
                        <xsd:attribute name="traceId" type="xsd:string"/>
                    </xsd:attributeGroup>

                    <xsd:complexType name="BaseType">
                        <xsd:attributeGroup ref="tns:TraceAttributes"/>
                        <xsd:anyAttribute namespace="##any" processContents="skip"/>
                    </xsd:complexType>

                    <xsd:complexType name="DerivedType">
                        <xsd:complexContent>
                            <xsd:extension base="tns:BaseType"/>
                        </xsd:complexContent>
                    </xsd:complexType>

                    <xsd:element name="Derived" type="tns:DerivedType"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun complexContentRestrictionAttributeWsdl(namespace: String): String =
        """
        <definitions name="ComplexContentRestrictionAttributes"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:attributeGroup name="TraceAttributes">
                        <xsd:attribute name="traceId" type="xsd:string"/>
                    </xsd:attributeGroup>

                    <xsd:complexType name="BaseType">
                        <xsd:sequence>
                            <xsd:element name="id" type="xsd:string"/>
                        </xsd:sequence>
                        <xsd:attributeGroup ref="tns:TraceAttributes"/>
                        <xsd:anyAttribute namespace="##any" processContents="skip"/>
                    </xsd:complexType>

                    <xsd:complexType name="DerivedType">
                        <xsd:complexContent>
                            <xsd:restriction base="tns:BaseType">
                                <xsd:sequence>
                                    <xsd:element name="id" type="xsd:string"/>
                                </xsd:sequence>
                                <xsd:attribute name="localCode" type="xsd:string"/>
                            </xsd:restriction>
                        </xsd:complexContent>
                    </xsd:complexType>

                    <xsd:element name="Derived" type="tns:DerivedType"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun xmlAttributeReferenceWsdl(namespace: String): String =
        """
        <definitions name="XmlAttributeRef"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:complexType name="PersonType">
                        <xsd:attribute ref="xml:id" use="required"/>
                    </xsd:complexType>

                    <xsd:element name="Person" type="tns:PersonType"/>
                </xsd:schema>

                <xsd:schema targetNamespace="http://www.w3.org/XML/1998/namespace"
                            elementFormDefault="unqualified"
                            attributeFormDefault="qualified">
                    <xsd:attribute name="id" type="xsd:ID"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

    private fun simpleContentExtensionAttributesWsdl(namespace: String): String =
        """
        <definitions name="SimpleContentExtensionAttributes"
                     targetNamespace="$namespace"
                     xmlns:tns="$namespace"
                     xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                     xmlns="http://schemas.xmlsoap.org/wsdl/">
            <types>
                <xsd:schema targetNamespace="$namespace" elementFormDefault="qualified">
                    <xsd:simpleType name="CodeEnum">
                        <xsd:restriction base="xsd:string"/>
                    </xsd:simpleType>

                    <xsd:attributeGroup name="CodeAttributes">
                        <xsd:attribute name="code" type="xsd:string"/>
                    </xsd:attributeGroup>

                    <xsd:complexType name="CodeType">
                        <xsd:simpleContent>
                            <xsd:extension base="tns:CodeEnum">
                                <xsd:attributeGroup ref="tns:CodeAttributes"/>
                            </xsd:extension>
                        </xsd:simpleContent>
                    </xsd:complexType>

                    <xsd:element name="Code" type="tns:CodeType"/>
                </xsd:schema>
            </types>
        </definitions>
        """.trimIndent()

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
        val pattern = soapElement.getSOAPPayload(
            nodeName,
            specmaticTypeName,
            namespaces,
            typeInfo,
        ).toPattern(RequestHeaders()) as XMLPattern
        val resolver = Resolver(newPatterns = typeInfo.types.mapKeys { (typeName, _) -> withPatternDelimiters(typeName) })
        return pattern.generateXML(resolver, IncludeOptionalXMLNodes).toStringLiteral()
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
