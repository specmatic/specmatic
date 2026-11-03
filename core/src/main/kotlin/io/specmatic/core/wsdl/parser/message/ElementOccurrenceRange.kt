package io.specmatic.core.wsdl.parser.message

import java.math.BigInteger
import io.specmatic.core.pattern.*
import io.specmatic.core.value.XMLNode

internal fun elementOccurrenceRange(node: XMLNode): XMLOccurrenceRange {
    fun bound(name: String): BigInteger {
        val literal = node.attributes[name]?.toStringLiteral()?.trim() ?: return BigInteger.ONE
        return literal.takeIf { it.matches(Regex("[+-]?[0-9]+")) }?.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }
            ?: throw ContractException("Invalid $name=\"$literal\"")
    }
    return try {
        XMLOccurrenceRange(bound("minOccurs"), if (node.attributes["maxOccurs"]?.toStringLiteral()?.trim() == "unbounded") null else bound("maxOccurs"))
    } catch (e: ContractException) {
        throw ContractException("${e.message} on ${node.oneLineDescription}", exceptionCause = e)
    }
}

internal fun validateSchemaOccurrenceRanges(schema: XMLNode, source: String) {
    fun validate(node: XMLNode, parentNode: XMLNode?) {
        val parent = parentNode?.name
        if (node.name in setOf("element", "any", "sequence", "choice", "all", "group")) {
            val range = elementOccurrenceRange(node)
            if (parent == "schema" && node.attributes.keys.any { it in setOf("minOccurs", "maxOccurs") })
                throw ContractException("Occurrence bounds are not allowed on global ${node.oneLineDescription}")
            if (parent == "group" && parentNode.attributes.containsKey("name") && node.attributes.keys.any { it in setOf("minOccurs", "maxOccurs") })
                throw ContractException("Occurrence bounds are not allowed on a named group's compositor: ${node.oneLineDescription}")
            if (node.name == "all" && (range.minimum > BigInteger.ONE || range.maximum != BigInteger.ONE))
                throw ContractException("XSD 1.0 all requires minOccurs 0 or 1 and maxOccurs 1 on ${node.oneLineDescription}")
            if (parent == "all" && (node.name != "element" || range.minimum > BigInteger.ONE || range.maximum == null || range.maximum > BigInteger.ONE))
                throw ContractException("XSD 1.0 all members must be elements occurring at most once on ${node.oneLineDescription}")
            if (node.name == "all" && parent !in setOf("complexType", "group", "extension", "restriction"))
                throw ContractException("XSD 1.0 all cannot be nested in $parent on ${node.oneLineDescription}")
        }
        node.childNodes.filterIsInstance<XMLNode>().filterNot { it.name == "annotation" }.forEach { validate(it, node) }
    }
    try {
        validate(schema, null)
    } catch (e: ContractException) {
        throw ContractException("${e.message} in $source", exceptionCause = e)
    }
}

internal fun Pattern.withOccurrenceRange(range: XMLOccurrenceRange): Pattern = when {
    this is XMLPattern -> copy(pattern = pattern.copy(occurrences = range))
    range.fitsIntBounds && this is XMLChoiceGroupPattern -> copy(minOccurs = range.min, maxOccurs = range.max)
    range.fitsIntBounds && this is XMLWildcardPattern -> copy(minOccurs = range.min, maxOccurs = range.max)
    else -> XMLParticlePattern(this, range)
}
