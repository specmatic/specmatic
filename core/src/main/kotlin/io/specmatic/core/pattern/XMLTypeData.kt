package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.XMLNode
import io.specmatic.core.wsdl.parser.message.*

data class XMLTypeData(
    val name: String = "",
    val realName: String,
    val attributes: Map<String, Pattern> = emptyMap(),
    val nodes: List<Pattern> = emptyList(),
    val isSOAP: Boolean = false,
    val namespaceUri: String? = null,
    val attributeWildcards: List<XMLAttributeWildcard> = emptyList(),
) {
    fun hasType(): Boolean = attributes.containsKey(TYPE_ATTRIBUTE_NAME)
    fun hasBeenDereferenced(): Boolean = hasType() && nodes.isNotEmpty()

    fun isConcrete(): Boolean {
        return !hasType() || hasBeenDereferenced()
    }

    fun getAttributeValue(name: String): String? =
        (attributes[name] as ExactValuePattern?)?.pattern?.toStringLiteral()

    fun isEmpty(): Boolean {
        return name.isEmpty() && attributes.isEmpty() && nodes.isEmpty()
    }

    fun toGherkinString(additionalIndent: String = "", indent: String = ""): String {
        val attributeText = attributes.entries.joinToString(" ") { (key, value) -> "$key=\"$value\"" }.let { if(it.isNotEmpty()) " $it" else ""}

        return when {
            nodes.isEmpty() -> {
                return "$indent<$realName$attributeText/>"
            }
            nodes.size == 1 && nodes.first() !is XMLPattern && nodes.first() !is XMLWildcardPattern -> {
                val bodyText = nodes.first().pattern.toString()
                "$indent<$realName$attributeText>$bodyText</$realName>"
            }
            else -> {
                val childNodeText = nodes.flatMap {
                    when (it) {
                        is XMLPattern -> listOf(it.toGherkinString(additionalIndent, indent + additionalIndent))
                        is XMLWildcardPattern -> (it.generate(Resolver()) as XMLNode).childNodes.map { generated ->
                            XMLPattern(generated as XMLNode).toGherkinString(additionalIndent, indent + additionalIndent)
                        }
                        else -> throw ContractException("Expected an xml node: $it")
                    }
                }.joinToString("\n")

                if (childNodeText.isBlank())
                    return "$indent<$realName$attributeText/>"

                "$indent<$realName$attributeText>\n$childNodeText\n$indent</$realName>"
            }
        }
    }

    fun toGherkinishNode(): XMLNode {
        val childXMLNodes = nodes.map {
            when(it) {
                is XMLPattern -> it.toGherkinXMLNode()
                is XMLWildcardPattern -> null
                else -> StringValue(it.pattern.toString())
            }
        }.filterNotNull()

        return XMLNode(realName, attributes.mapValues { StringValue(it.value.pattern.toString()) }, childXMLNodes)
    }

    fun isOptionalNode(): Boolean {
        return attributes[OCCURS_ATTRIBUTE_NAME].let {
            it is ExactValuePattern && it.pattern.toStringLiteral() == OPTIONAL_ATTRIBUTE_VALUE
        }
    }

    fun isMultipleNode(): Boolean {
        return attributes[OCCURS_ATTRIBUTE_NAME].let {
            it is ExactValuePattern && it.pattern.toStringLiteral() == MULTIPLE_ATTRIBUTE_VALUE
        }
    }

    fun getNodeOccurrence(): NodeOccurrence {
        val attributeType = (attributes[OCCURS_ATTRIBUTE_NAME]) as ExactValuePattern?

        return when(attributeType?.pattern?.toStringLiteral()) {
            "optional" -> NodeOccurrence.Optional
            "multiple" -> NodeOccurrence.Multiple
            else -> NodeOccurrence.Once
        }
    }

    fun isNillable(): Boolean {
        return attributes[NILLABLE_ATTRIBUTE_NAME].let {
            it is ExactValuePattern && it.pattern.toStringLiteral().lowercase() == "true"
        }
    }
}
