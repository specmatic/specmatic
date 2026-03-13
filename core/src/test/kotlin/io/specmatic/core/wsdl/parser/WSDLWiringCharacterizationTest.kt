package io.specmatic.core.wsdl.parser

import io.specmatic.core.pattern.TYPE_ATTRIBUTE_NAME
import io.specmatic.core.value.FullyQualifiedName
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.localName
import io.specmatic.core.value.toXMLNode
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.payload.RequestHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class WSDLWiringCharacterizationTest {
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
        assertThat(typeInfo.types.getValue("CreateOrderType").toPrettyString())
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
        assertThat(typeInfo.types.getValue("QualifiedAddress_inlineStatus").toPrettyString())
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
        assertThat(typeInfo.types.getValue("TradePriceRequestType").toPrettyString())
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

        assertThat(typeInfo.types.getValue("ExtendedElementType").toPrettyString())
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

        assertThat(typeInfo.types.getValue("SimpleExtensionElementType").toPrettyString())
            .contains("IdentifierType")
            .contains("(string)")
        assertThat(requestBody).contains("<SimpleExtensionElement specmatic_type=\"SimpleExtensionElementType\"/>")
    }

    @Test
    fun `imported schema wiring still resolves downstream children from imported xsd files`() {
        val wsdl = loadWsdl("src/test/resources/wsdl/imported_types.wsdl")

        val soapElement = wsdl.getSOAPElement(
            FullyQualifiedName("tns", "http://example.com/imported-wsdl", "ImportedPerson")
        )

        val typeInfo = soapElement.deriveSpecmaticTypes("ImportedPersonType", emptyMap(), emptySet())
        val requestBody = soapBody(soapElement, wsdl, "ImportedPerson", "ImportedPersonType", typeInfo)

        assertThat(typeInfo.types.getValue("ImportedPersonType").toPrettyString())
            .contains("<name>(string)</name>")
        assertThat(requestBody).contains("<ImportedPerson specmatic_type=\"ImportedPersonType\"/>")
    }

    private fun loadWsdl(path: String): WSDL {
        val wsdlFile = File(path)
        return WSDL(toXMLNode(wsdlFile.readText()), wsdlFile.canonicalPath)
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
}
