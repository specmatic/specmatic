package io.specmatic.core.wsdl.parser.message

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.XMLParticlePattern
import io.specmatic.core.pattern.XMLSequencePattern
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.WSDL
import io.specmatic.core.wsdl.parser.WSDLTypeInfo

internal class GroupReferenceInComplexType(
    private val reference: XMLNode,
    private val wsdl: WSDL,
    private val parentTypeName: String,
    private val allowAllGroup: Boolean
) : ComplexTypeChild {
    override fun process(wsdlTypeInfos: List<WSDLTypeInfo>, existingTypes: Map<String, Pattern>, typeStack: Set<String>): List<WSDLTypeInfo> {
        val range = elementOccurrenceRange(reference)
        val name = reference.fullyQualifiedNameFromAttribute("ref")
        val key = "group:{${name.namespace}}${name.localName}"
        if (key in typeStack) throw ContractException("Cyclic model group reference $key")
        val definition = wsdl.findGroup(name, reference.schema)
        if (definition.childNodes.filterIsInstance<XMLNode>().any { it.name == "all" } &&
            (!allowAllGroup || range.minimum > java.math.BigInteger.ONE || range.maximum != java.math.BigInteger.ONE))
            throw ContractException("XSD 1.0 all group $key requires minOccurs 0 or 1, maxOccurs 1, and cannot be nested in another model group: ${reference.oneLineDescription}")
        if (range.isZero) return wsdlTypeInfos
        val variants = generateChildren(parentTypeName, definition, existingTypes, typeStack + key, wsdl)
        return combineVariants(wsdlTypeInfos, variants.map { variant ->
            variant.copy(members = listOf(XMLParticlePattern(XMLSequencePattern(variant.effectiveMembers), range)))
        })
    }
}
