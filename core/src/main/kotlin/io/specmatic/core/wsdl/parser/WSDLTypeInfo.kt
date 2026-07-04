package io.specmatic.core.wsdl.parser

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.AnyPattern
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.WSDLSubstitutionGroupMember
import io.specmatic.core.pattern.WSDLTypeDerivationMethod
import io.specmatic.core.value.CDATAValue
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue

data class WSDLTypeInfo(
    val nodes: List<XMLValue> = emptyList(),
    val members: List<Pattern> = emptyList(),
    val types: Map<String, Pattern> = emptyMap(),
    val namespacePrefixes: Set<String> = emptySet(),
    val wsdlTypeNamespace: String? = null,
    val wsdlTypeName: String? = null,
    val wsdlBaseTypeNamespace: String? = null,
    val wsdlBaseTypeName: String? = null,
    val wsdlBaseTypeDerivationMethod: WSDLTypeDerivationMethod? = null,
    val wsdlTypeIsAbstract: Boolean = false,
) {
    fun getNamespaces(wsdlNamespaces: Map<String, String>): Map<String, String> {
        logger.debug(wsdlNamespaces.toString())
        logger.debug(namespacePrefixes.toString())

        return namespacePrefixes.toList().associateWith {
            wsdlNamespaces.getValue(it)
        }
    }

    fun plus(otherWSDLTypeInfo: WSDLTypeInfo): WSDLTypeInfo {
        return WSDLTypeInfo(
            this.nodes.plus(otherWSDLTypeInfo.nodes),
            this.effectiveMembers.plus(otherWSDLTypeInfo.effectiveMembers),
            this.types.plus(otherWSDLTypeInfo.types),
            this.namespacePrefixes.plus(otherWSDLTypeInfo.namespacePrefixes),
            this.wsdlTypeNamespace ?: otherWSDLTypeInfo.wsdlTypeNamespace,
            this.wsdlTypeName ?: otherWSDLTypeInfo.wsdlTypeName,
            this.wsdlBaseTypeNamespace ?: otherWSDLTypeInfo.wsdlBaseTypeNamespace,
            this.wsdlBaseTypeName ?: otherWSDLTypeInfo.wsdlBaseTypeName,
            this.wsdlBaseTypeDerivationMethod ?: otherWSDLTypeInfo.wsdlBaseTypeDerivationMethod,
            this.wsdlTypeIsAbstract || otherWSDLTypeInfo.wsdlTypeIsAbstract,
        )
    }

    val effectiveMembers: List<Pattern>
        get() = if (members.isNotEmpty()) members else nodes.map(::toPattern)

    fun withSubstitutionGroupMembers(substitutionGroupMembers: List<WSDLSubstitutionGroupMember>): WSDLTypeInfo {
        if (substitutionGroupMembers.isEmpty()) return this
        return copy(members = members.map { withSubstitutionGroupMembers(it, substitutionGroupMembers) })
    }

    val xmlTypeData: XMLTypeData
        get() {
            return XMLTypeData(
                TYPE_NODE_NAME,
                TYPE_NODE_NAME,
                nodes = effectiveMembers,
                wsdlTypeNamespace = wsdlTypeNamespace,
                wsdlTypeName = wsdlTypeName,
                wsdlBaseTypeNamespace = wsdlBaseTypeNamespace,
                wsdlBaseTypeName = wsdlBaseTypeName,
                wsdlBaseTypeDerivationMethod = wsdlBaseTypeDerivationMethod,
                wsdlTypeIsAbstract = wsdlTypeIsAbstract,
            )
        }

    private fun toPattern(xmlValue: XMLValue): Pattern {
        return when (xmlValue) {
            is XMLNode -> XMLPattern(xmlValue)
            is StringValue, is CDATAValue, is BinaryValue -> xmlValue.exactMatchElseType()
        }
    }

    private fun withSubstitutionGroupMembers(
        pattern: Pattern,
        substitutionGroupMembers: List<WSDLSubstitutionGroupMember>
    ): Pattern {
        return when (pattern) {
            is XMLPattern -> pattern.copy(
                pattern = pattern.pattern.copy(
                    wsdlSubstitutionGroupMembers = substitutionGroupMembers.associateBy { it.elementName }
                )
            )

            is AnyPattern -> pattern.copy(pattern = pattern.pattern.map { withSubstitutionGroupMembers(it, substitutionGroupMembers) })
            else -> pattern
        }
    }
}

internal fun XMLNode.isAbstractNamedComplexType(): Boolean =
    name == "complexType" &&
            attributes.containsKey("name") &&
            attributes["abstract"]?.toStringLiteral()?.lowercase() == "true"
