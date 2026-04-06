package io.specmatic.core.wsdl.parser

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.XMLPattern
import io.specmatic.core.pattern.XMLTypeData
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.value.CDATAValue
import io.specmatic.core.value.BinaryValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue

data class WSDLTypeInfo(
    val nodes: List<XMLValue> = emptyList(),
    val members: List<Pattern> = emptyList(),
    val types: Map<String, Pattern> = emptyMap(),
    val namespacePrefixes: Set<String> = emptySet()
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
            this.namespacePrefixes.plus(otherWSDLTypeInfo.namespacePrefixes)
        )
    }

    val effectiveMembers: List<Pattern>
        get() = if (members.isNotEmpty()) members else nodes.map(::toPattern)

    val xmlTypeData: XMLTypeData
        get() {
            return XMLTypeData(TYPE_NODE_NAME, TYPE_NODE_NAME, nodes = effectiveMembers)
        }

    private fun toPattern(xmlValue: XMLValue): Pattern {
        return when (xmlValue) {
            is XMLNode -> XMLPattern(xmlValue)
            is StringValue, is CDATAValue, is BinaryValue -> xmlValue.exactMatchElseType()
        }
    }
}
