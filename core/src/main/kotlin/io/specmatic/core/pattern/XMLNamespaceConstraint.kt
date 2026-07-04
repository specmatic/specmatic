package io.specmatic.core.pattern

import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.Result.Failure
import io.specmatic.core.Result.Success
import io.specmatic.core.dataTypeMismatchResult
import io.specmatic.core.patternMismatchResult
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import io.specmatic.core.value.XMLNode
import io.specmatic.core.value.XMLValue
import io.specmatic.core.value.toXMLNode

private const val GENERATED_WILDCARD_PREFIX = "specmatic_any"
private const val GENERATED_WILDCARD_NAMESPACE = "urn:specmatic:xml-wildcard"

enum class XMLProcessContents {
    Strict,
    Lax,
    Skip;

    companion object {
        fun from(attributeValue: String?): XMLProcessContents {
            return when (attributeValue) {
                "lax" -> Lax
                "skip" -> Skip
                else -> Strict
            }
        }
    }
}

sealed interface XMLNamespaceConstraint {
    fun allows(namespaceUri: String?): Boolean
    fun encompasses(other: XMLNamespaceConstraint): Boolean
    fun representativeNamespace(targetNamespace: String?): String?
    val description: String
}

object AnyXMLNamespace : XMLNamespaceConstraint {
    override fun allows(namespaceUri: String?): Boolean = true
    override fun encompasses(other: XMLNamespaceConstraint): Boolean = true
    override fun representativeNamespace(targetNamespace: String?): String = generatedNamespaceDifferentFrom(targetNamespace)
    override val description: String = "##any"
}

data class XMLNamespaceList(val namespaces: Set<String?>) : XMLNamespaceConstraint {
    override fun allows(namespaceUri: String?): Boolean = namespaceUri in namespaces

    override fun encompasses(other: XMLNamespaceConstraint): Boolean {
        return when (other) {
            AnyXMLNamespace -> false
            is XMLNamespaceList -> namespaces.containsAll(other.namespaces)
            is XMLNotNamespaceList -> false
        }
    }

    override fun representativeNamespace(targetNamespace: String?): String? =
        namespaces.firstOrNull { it != null } ?: namespaces.firstOrNull()

    override val description: String = namespaces.joinToString(" ") { it ?: "##local" }
}

data class XMLNotNamespaceList(val excludedNamespaces: Set<String?>) : XMLNamespaceConstraint {
    override fun allows(namespaceUri: String?): Boolean = namespaceUri !in excludedNamespaces

    override fun encompasses(other: XMLNamespaceConstraint): Boolean {
        return when (other) {
            AnyXMLNamespace -> false
            is XMLNamespaceList -> other.namespaces.none { it in excludedNamespaces }
            is XMLNotNamespaceList -> excludedNamespaces.all { it in other.excludedNamespaces }
        }
    }

    override fun representativeNamespace(targetNamespace: String?): String =
        listOf(
            generatedNamespaceDifferentFrom(targetNamespace),
            "$GENERATED_WILDCARD_NAMESPACE-other"
        ).first { allows(it) }

    override val description: String = "not(${excludedNamespaces.joinToString(" ") { it ?: "##local" }})"
}

fun xmlNamespaceConstraint(namespaceValue: String?, targetNamespace: String?): XMLNamespaceConstraint {
    val tokens = namespaceValue?.trim()?.takeIf { it.isNotBlank() }?.split(Regex("\\s+")) ?: listOf("##any")

    return when {
        tokens == listOf("##any") -> AnyXMLNamespace
        tokens == listOf("##other") -> XMLNotNamespaceList(setOf(targetNamespace, null))
        else -> XMLNamespaceList(tokens.map { namespaceTokenValue(it, targetNamespace) }.toSet())
    }
}

private fun namespaceTokenValue(token: String, targetNamespace: String?): String? {
    return when (token) {
        "##targetNamespace" -> targetNamespace
        "##local" -> null
        else -> token
    }
}

private fun generatedNamespaceDifferentFrom(targetNamespace: String?): String {
    return when (targetNamespace) {
        GENERATED_WILDCARD_NAMESPACE -> "$GENERATED_WILDCARD_NAMESPACE-other"
        else -> GENERATED_WILDCARD_NAMESPACE
    }
}

data class XMLOccurrenceRange(val min: Int, val max: Int?) {
    fun encompasses(other: XMLOccurrenceRange): Boolean {
        return min <= other.min && when {
            max == null -> true
            other.max == null -> false
            else -> max >= other.max
        }
    }
}

fun NodeOccurrence.toOccurrenceRange(): XMLOccurrenceRange {
    return when (this) {
        NodeOccurrence.Multiple -> XMLOccurrenceRange(0, null)
        NodeOccurrence.Optional -> XMLOccurrenceRange(0, 1)
        NodeOccurrence.Once -> XMLOccurrenceRange(1, 1)
    }
}

fun XMLTypeData.occurrenceRange(): XMLOccurrenceRange = getNodeOccurrence().toOccurrenceRange()

data class XMLAttributeWildcard(
    val namespaceConstraint: XMLNamespaceConstraint,
    val processContents: XMLProcessContents = XMLProcessContents.Strict
) {
    fun allows(attributeName: String, ownerNode: XMLNode): Boolean =
        namespaceConstraint.allows(ownerNode.attributeNamespaceUri(attributeName))

    fun encompasses(other: XMLAttributeWildcard): Boolean =
        namespaceConstraint.encompasses(other.namespaceConstraint)
}

data class XMLWildcardPattern(
    val namespaceConstraint: XMLNamespaceConstraint,
    val processContents: XMLProcessContents = XMLProcessContents.Strict,
    val minOccurs: Int = 1,
    val maxOccurs: Int? = 1,
    val targetNamespace: String? = null,
    override val typeAlias: String? = null
) : Pattern, XMLChildGenerationPattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when {
            sampleData !is XMLNode -> dataTypeMismatchResult("xml wildcard", sampleData, resolver.mismatchMessages)
            namespaceConstraint.allows(sampleData.elementNamespaceUri()) -> Success()
            else -> Failure("XML node namespace ${sampleData.elementNamespaceUri() ?: "##local"} is not allowed by wildcard $description")
        }
    }

    override fun matches(sampleData: List<Value>, resolver: Resolver): ConsumeResult<Value, Value> {
        val xmlValues = sampleData.filterIsInstance<XMLValue>()
        if (xmlValues.size != sampleData.size)
            return ConsumeResult(Failure("XMLWildcardPattern can only match XML values"), sampleData)

        val candidates = matchCandidates(xmlValues, resolver)
        return candidates.firstOrNull()?.cast("xml") ?: ConsumeResult(Failure("XML wildcard did not match"), sampleData)
    }

    fun matchCandidates(sampleData: List<XMLValue>, resolver: Resolver): List<ConsumeResult<XMLValue, Value>> {
        val maximum = maxOccurs ?: sampleData.size
        val matchingCount = sampleData.take(maximum).takeWhile {
            it is XMLNode && matches(it, resolver) is Success
        }.size

        if (matchingCount < minOccurs) {
            return listOf(ConsumeResult(Failure("Expected at least $minOccurs XML wildcard node(s), but found $matchingCount"), sampleData))
        }

        return (minOccurs..matchingCount).toList().asReversed().map { count ->
            ConsumeResult(Success(), sampleData.drop(count))
        }
    }

    fun encompassCandidates(
        otherPatterns: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): List<ConsumeResult<Pattern, Pattern>> {
        val maximum = maxOccurs ?: otherPatterns.size
        val matchingCount = otherPatterns.take(maximum).takeWhile {
            encompasses(it, thisResolver, otherResolver, typeStack) is Success
        }.size

        if (matchingCount < minOccurs) {
            return listOf(ConsumeResult(Failure("Expected at least $minOccurs XML wildcard node pattern(s), but found $matchingCount"), otherPatterns))
        }

        return (minOccurs..matchingCount).toList().asReversed().map { count ->
            ConsumeResult(Success(), otherPatterns.drop(count))
        }
    }

    override fun generate(resolver: Resolver): Value =
        generateXMLNodes(resolver, XMLGenerationState()).asContainer()

    override fun generateXMLNodes(resolver: Resolver, state: XMLGenerationState): GeneratedNodes {
        val generatedNodes = 0.until(state.decisions.numberOfXMLNodesFor(minOccurs, maxOccurs))
            .map { generatedNode() }
        return GeneratedNodes(generatedNodes, state)
    }

    override fun generateXMLChildValues(resolver: Resolver): List<XMLValue> {
        return generatedContainerChildValues(resolver)
    }

    private fun generatedNode(): XMLNode {
        val namespace = namespaceConstraint.representativeNamespace(targetNamespace)

        return when (namespace) {
            null -> XMLNode("any", emptyMap(), listOf(StringValue("value")))
            else -> XMLNode(
                "$GENERATED_WILDCARD_PREFIX:any",
                mapOf("xmlns:$GENERATED_WILDCARD_PREFIX" to StringValue(namespace)),
                listOf(StringValue("value"))
            )
        }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> =
        sequenceOf(HasValue(this))

    override fun negativeBasedOn(
        row: Row,
        resolver: Resolver,
        config: NegativePatternConfiguration
    ): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(this))

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> = sequenceOf(this)

    override fun parse(value: String, resolver: Resolver): Value = toXMLNode(value)

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val other = resolvedHop(otherPattern, otherResolver)

        return when (other) {
            is AnyPattern -> Result.fromResults(other.pattern.map { encompasses(it, thisResolver, otherResolver, typeStack) })
            is XMLWildcardPattern -> when {
                !namespaceConstraint.encompasses(other.namespaceConstraint) ->
                    Failure("XML wildcard namespace $description does not encompass ${other.description}")
                !occurrenceRange.encompasses(other.occurrenceRange) ->
                    Failure("XML wildcard occurrence range $occurrenceRange does not encompass ${other.occurrenceRange}")
                else -> Success()
            }
            is XMLPattern -> encompassesXMLPattern(other)
            is ExactValuePattern -> encompassesExactValue(other)
            else -> patternMismatchResult(this, other, thisResolver.mismatchMessages)
        }
    }

    private fun encompassesXMLPattern(other: XMLPattern): Result {
        val otherNamespace = other.pattern.namespaceUri

        return when {
            !namespaceConstraint.allows(otherNamespace) ->
                Failure("XML wildcard namespace $description does not allow ${otherNamespace ?: "##local"}")
            !occurrenceRange.encompasses(other.pattern.occurrenceRange()) ->
                Failure("XML wildcard occurrence range $occurrenceRange does not encompass ${other.pattern.occurrenceRange()}")
            else -> Success()
        }
    }

    private fun encompassesExactValue(other: ExactValuePattern): Result {
        val xmlNode = other.pattern as? XMLNode ?: return Failure("Expected XML node")

        return when {
            namespaceConstraint.allows(xmlNode.elementNamespaceUri()) -> Success()
            else -> Failure("XML wildcard namespace $description does not allow ${xmlNode.elementNamespaceUri() ?: "##local"}")
        }
    }

    override fun encompasses(
        others: List<Pattern>,
        thisResolver: Resolver,
        otherResolver: Resolver,
        lengthError: String,
        typeStack: TypeStack
    ): ConsumeResult<Pattern, Pattern> =
        encompassCandidates(others, thisResolver, otherResolver, typeStack).firstOrNull()
            ?: ConsumeResult(Failure(lengthError), others)

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value =
        XMLNode.container(valueList.map { it as XMLValue })

    override val typeName: String = "xml-wildcard"
    override val pattern: Any
        get() = "(xml-wildcard $description)"

    private val occurrenceRange: XMLOccurrenceRange
        get() = XMLOccurrenceRange(minOccurs, maxOccurs)
    private val description: String = "${namespaceConstraint.description} ${minOccurs}..${maxOccurs ?: "unbounded"}"
}
